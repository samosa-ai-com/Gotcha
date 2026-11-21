// Binder interface between Gotcha and Gotcha Shell.
//
// Gotcha carries a copy of this directory. From interface version 2 on,
// changes are additive only: never reorder, rename or remove methods or
// fields. Add new ones at the end, raise ShellService.INTERFACE_VERSION and
// note the change in CHANGELOG.md.
package com.gotcha.shell;

import com.gotcha.shell.IInstallCallback;
import com.gotcha.shell.JobStatus;
import com.gotcha.shell.RunRequest;
import com.gotcha.shell.RunResult;
import com.gotcha.shell.SessionHandle;
import com.gotcha.shell.ShellInfo;

interface IShellService {
    ShellInfo getInfo();

    /** Runs request.cmd with /bin/sh -c and waits. Output is capped at 32 KB per stream. */
    RunResult run(in RunRequest request);

    /**
     * Starts a login shell on a pty; returns our end of a socket pair carrying its bytes.
     * Counts toward the session cap. Throws IllegalStateException("Too many sessions") at the cap.
     */
    ParcelFileDescriptor openSession(int rows, int cols);

    /**
     * Downloads, verifies and unpacks the rootfs, replacing any existing one.
     * Blocks until done; progress goes to callback. Returns null on success,
     * otherwise an error message.
     */
    @nullable String install(IInstallCallback callback);

    /** Rewrites the companion's config files in the rootfs and finishes interrupted package installs. */
    RunResult repair();

    /** Kills all jobs and sessions and deletes the rootfs. */
    void reset();

    /** Starts request.cmd in the background; returns a job id. Throws IllegalStateException when too many jobs run. */
    String startJob(in RunRequest request);

    /**
     * Status of a job and its output from the given byte offsets (at most
     * 32 KB per stream per call). Pass the returned offsets to the next call.
     */
    JobStatus pollJob(String jobId, long stdoutOffset, long stderrOffset);

    /** Kills a running job. Returns false if the job is unknown or already finished. */
    boolean killJob(String jobId);

    /** Writes everything read from source to guestPath, creating parent directories. */
    RunResult importFile(in ParcelFileDescriptor source, String guestPath);

    /** Opens guestPath for reading, or returns null if it is not a readable regular file. */
    @nullable ParcelFileDescriptor exportFile(String guestPath);

    // Added in interface version 3.

    /** Like openSession, but also returns the session's id. Same cap and errors. */
    SessionHandle openSessionHandle(int rows, int cols);

    /** Sets the pty's window size. Returns false if the id is unknown or the session ended. */
    boolean resizeSession(String sessionId, int rows, int cols);

    /** Hangs up the session. Returns false if the id is unknown. */
    boolean closeSession(String sessionId);

    // Added in interface version 4.

    /**
     * Downloads, verifies and installs the Linux runtime (PRoot), which is
     * not part of the companion's APK. Blocks until done; progress goes to
     * callback with the phases "download-runtime", "verify-runtime" and
     * "install-runtime". Returns null on success, or an error message.
     * Returns null immediately when it is already installed.
     *
     * Calling it is optional: every call that needs the runtime installs it
     * first anyway. It exists so the user can be shown progress instead of
     * a command that takes an unexplained extra few seconds.
     */
    @nullable String installRuntime(IInstallCallback callback);

    // Added in interface version 5.

    /**
     * Tells the companion where to fetch the Linux runtime from, for the case
     * where Gotcha has already asked the backend on the user's behalf.
     *
     * Gotcha holds the Samosa session; this app does not and never will
     * (AGENTS.md: the interface is one-way, no credential on the companion).
     * While the runtime's distribution repository is private, a signed URL can
     * only be obtained by asking the backend, so Gotcha fetches it and passes
     * the answer here before the first call that needs the runtime.
     *
     * [url] must be an https URL and [sha256] 64 lowercase hex characters, or
     * this throws IllegalStateException. The checksum must also equal the pin
     * compiled into this build (the one runtime it will install), or the
     * install is refused: a pushed URL chooses where the bytes come from, never
     * which bytes they are. The checksum is checked against the downloaded
     * bytes as well.
     *
     * Optional: without it the companion asks the backend itself. Calling it
     * repeatedly is allowed; the most recent answer wins.
     */
    void offerRuntime(String version, String url, String sha256);
}
