package com.termux.terminal;

import android.os.Handler;
import android.os.Looper;

import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Gotcha's own replacement for Termux's {@code TerminalSession}; it is not part of the Termux
 * sources copied into this module, and it keeps the name and the methods Termux's view calls so
 * that the copied view stays byte for byte what Termux ships.
 *
 * Termux's class starts a local process on a pty through native code. Gotcha's shell lives in
 * another app, so this one is given a stream of what the shell printed and a {@link Host} to send
 * keystrokes and size changes to. Nothing here starts a process.
 *
 * Threads: the emulator is only touched on the main thread. One thread reads the shell's output
 * into a bounded queue (so a program that floods output waits instead of flooding the main
 * thread), and one thread sends to the host, in order, so a resize never overtakes the keystroke
 * before it.
 */
public final class TerminalSession extends TerminalOutput {

    /** Where keystrokes go, and who learns about size changes. Called off the main thread. */
    public interface Host {
        /** Sends bytes the user typed to the shell. */
        void write(byte[] data, int offset, int count);

        /** The terminal has this many rows and columns now; tell the pty. */
        void resize(int rows, int columns);

        /** The view side is done; hang the shell up. */
        void hangUp();
    }

    private static final int QUEUE_BYTES = 64 * 1024;
    private static final int READ_BYTES = 4096;
    private static final int MAX_CODE_POINT = 0x10FFFF;
    private static final int SURROGATE_FIRST = 0xD800;
    private static final int SURROGATE_LAST = 0xDFFF;

    private final InputStream mInput;
    private final Host mHost;
    private final Integer mTranscriptRows;
    private final Handler mMain = new Handler(Looper.getMainLooper());
    private final ByteQueue mQueue = new ByteQueue(QUEUE_BYTES);
    private final ExecutorService mWriter = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "terminal-writer");
        thread.setDaemon(true);
        return thread;
    });
    private final AtomicBoolean mDrainPending = new AtomicBoolean(false);
    private final byte[] mDrainBuffer = new byte[READ_BYTES];
    private final byte[] mUtf8InputBuffer = new byte[5];

    private TerminalSessionClient mClient;
    private TerminalEmulator mEmulator;
    private volatile boolean mRunning = true;
    private boolean mFinishedReported;

    /**
     * @param input          what the shell prints; read until it ends
     * @param host           receives keystrokes and size changes
     * @param client         told when the screen changed, the title changed or the session ended
     * @param transcriptRows lines of scrollback, or null for the emulator's default
     */
    public TerminalSession(InputStream input, Host host, TerminalSessionClient client, Integer transcriptRows) {
        mInput = input;
        mHost = host;
        mClient = client;
        mTranscriptRows = transcriptRows;
    }

    public void updateTerminalSessionClient(TerminalSessionClient client) {
        mClient = client;
        if (mEmulator != null) mEmulator.updateTerminalSessionClient(client);
    }

    /** Resizes the emulator and tells the pty, or starts the emulator on the first call. */
    public void updateSize(int columns, int rows, int cellWidthPixels, int cellHeightPixels) {
        if (mEmulator == null) {
            initializeEmulator(columns, rows, cellWidthPixels, cellHeightPixels);
        } else {
            mEmulator.resize(columns, rows, cellWidthPixels, cellHeightPixels);
            tellHostSize(rows, columns);
        }
    }

    /** Starts the emulator at this size and begins reading the shell's output. */
    public void initializeEmulator(int columns, int rows, int cellWidthPixels, int cellHeightPixels) {
        mEmulator = new TerminalEmulator(this, columns, rows, cellWidthPixels, cellHeightPixels, mTranscriptRows, mClient);
        // The session was opened at a guessed size; the real one is known only now.
        tellHostSize(rows, columns);
        Thread reader = new Thread(this::readLoop, "terminal-reader");
        reader.setDaemon(true);
        reader.start();
    }

    private void tellHostSize(int rows, int columns) {
        send(() -> mHost.resize(rows, columns));
    }

    /** Queues work for the host thread; once the session is finished there is nobody to tell. */
    private void send(Runnable work) {
        try {
            mWriter.execute(work);
        } catch (RejectedExecutionException e) {
            // Shut down by finishIfRunning.
        }
    }

    private void readLoop() {
        byte[] buffer = new byte[READ_BYTES];
        try {
            int count = mInput.read(buffer);
            while (count >= 0) {
                if (count > 0 && !mQueue.write(buffer, 0, count)) return;
                requestDrain();
                count = mInput.read(buffer);
            }
        } catch (IOException e) {
            // The socket was closed from either side: the same as the shell ending.
        }
        // The queue is not closed here: closing it makes reads fail with bytes still stored.
        mMain.post(this::finish);
    }

    private void requestDrain() {
        if (mDrainPending.compareAndSet(false, true)) mMain.post(this::drain);
    }

    private void drain() {
        mDrainPending.set(false);
        boolean any = false;
        int count = mQueue.read(mDrainBuffer, false);
        while (count > 0) {
            if (mEmulator != null) mEmulator.append(mDrainBuffer, count);
            any = true;
            count = mQueue.read(mDrainBuffer, false);
        }
        if (any && mClient != null) mClient.onTextChanged(this);
    }

    private void finish() {
        drain();
        mRunning = false;
        if (!mFinishedReported) {
            mFinishedReported = true;
            if (mClient != null) mClient.onSessionFinished(this);
        }
    }

    /** Sends what the user typed to the shell. The bytes are copied; the caller may reuse them. */
    @Override
    public void write(byte[] data, int offset, int count) {
        if (!mRunning || count <= 0) return;
        byte[] copy = new byte[count];
        System.arraycopy(data, offset, copy, 0, count);
        send(() -> mHost.write(copy, 0, copy.length));
    }

    /** Writes one Unicode code point, UTF-8 encoded, optionally after an escape. */
    public void writeCodePoint(boolean prependEscape, int codePoint) {
        if (codePoint > MAX_CODE_POINT || (codePoint >= SURROGATE_FIRST && codePoint <= SURROGATE_LAST)) {
            throw new IllegalArgumentException("Invalid code point: " + codePoint);
        }
        int length = 0;
        if (prependEscape) mUtf8InputBuffer[length++] = 27;
        byte[] encoded = new String(Character.toChars(codePoint)).getBytes(java.nio.charset.StandardCharsets.UTF_8);
        System.arraycopy(encoded, 0, mUtf8InputBuffer, length, encoded.length);
        write(mUtf8InputBuffer, 0, length + encoded.length);
    }

    public TerminalEmulator getEmulator() {
        return mEmulator;
    }

    /** The title set by the program, or null if none or the emulator has not started. */
    public String getTitle() {
        return mEmulator == null ? null : mEmulator.getTitle();
    }

    public boolean isRunning() {
        return mRunning;
    }

    /** Clears the screen state, as a user-requested reset. */
    public void reset() {
        if (mEmulator == null) return;
        mEmulator.reset();
        if (mClient != null) mClient.onTextChanged(this);
    }

    /** Hangs the shell up and stops reading. Safe to call more than once. */
    public void finishIfRunning() {
        if (!mRunning) return;
        mRunning = false;
        send(mHost::hangUp);
        mWriter.shutdown();
        try {
            mInput.close();
        } catch (IOException e) {
            // Already closed.
        }
        mQueue.close();
    }

    @Override
    public void titleChanged(String oldTitle, String newTitle) {
        if (mClient != null) mClient.onTitleChanged(this);
    }

    @Override
    public void onCopyTextToClipboard(String text) {
        if (mClient != null) mClient.onCopyTextToClipboard(this, text);
    }

    @Override
    public void onPasteTextFromClipboard() {
        if (mClient != null) mClient.onPasteTextFromClipboard(this);
    }

    @Override
    public void onBell() {
        if (mClient != null) mClient.onBell(this);
    }

    @Override
    public void onColorsChanged() {
        if (mClient != null) mClient.onColorsChanged(this);
    }
}
