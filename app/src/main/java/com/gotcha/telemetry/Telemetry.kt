package com.gotcha.telemetry

import android.content.Context
import android.content.SharedPreferences
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import com.gotcha.BuildConfig
import com.gotcha.util.GotchaLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import java.util.UUID
import java.util.concurrent.Executor
import java.util.concurrent.Executors

/** Where events go. Both blank in a build without them, which turns telemetry off for good. */
data class TelemetryConfig(val url: String, val key: String) {
    val configured: Boolean get() = url.isNotBlank() && key.isNotBlank()
}

/** Whether the phone can send now, as the flush sees it. */
data class NetworkState(val connected: Boolean, val metered: Boolean)

/** What the Privacy page shows about usage data. */
data class TelemetryStatus(
    /** False in a build without an endpoint: the page hides the whole section. */
    val configured: Boolean,
    val enabled: Boolean,
    val mobileData: Boolean,
    /** True while an id exists that "Delete my data" can act on. */
    val hasInstallId: Boolean,
    /** The server asked for a pause, or refused this build's key. */
    val paused: Boolean,
    /** Epoch millis of the last accepted batch, 0 if none. */
    val lastSentAt: Long,
    val queued: Int
)

/** What one flush did, for tests and the debug log. */
enum class FlushOutcome { NOT_CONFIGURED, OFF, PAUSED, NOT_DUE, OFFLINE, METERED, EMPTY, SENT, FAILED }

/**
 * Opt-in usage data: off until the user turns it on in Settings ▸ Privacy, and
 * permanently off in a build without [TelemetryConfig].
 *
 * [track] only appends to a file; sending happens in [flush], which the app
 * calls when it goes to the background, when it starts, and from the reminder
 * alarm that is already armed every few hours. No scheduler of its own, so the
 * manifest gains nothing.
 */
class TelemetryClient internal constructor(
    private val config: TelemetryConfig,
    private val prefs: SharedPreferences,
    private val queue: TelemetryQueue,
    private val sender: TelemetrySender?,
    private val appInfo: () -> AppInfo,
    private val network: () -> NetworkState,
    private val clock: () -> Long = System::currentTimeMillis,
    private val random: () -> Double = Math::random,
    private val executor: Executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "telemetry").apply { isDaemon = true }
    }
) {
    private val flushLock = Mutex()

    /** Random per foreground session, never stored. */
    @Volatile
    var sessionId: String = UUID.randomUUID().toString()
        private set

    fun newSession() {
        sessionId = UUID.randomUUID().toString()
    }

    val isConfigured: Boolean get() = config.configured && sender != null

    /** True when an event tracked now would be queued. */
    val isActive: Boolean
        get() = isConfigured && enabled && installId.isNotBlank() && !paused()

    private val enabled: Boolean get() = prefs.getBoolean(KEY_ENABLED, false)
    private val installId: String get() = prefs.getString(KEY_INSTALL_ID, "").orEmpty()

    fun status(): TelemetryStatus = TelemetryStatus(
        configured = isConfigured,
        enabled = enabled,
        mobileData = prefs.getBoolean(KEY_MOBILE_DATA, false),
        hasInstallId = installId.isNotBlank(),
        paused = paused(),
        lastSentAt = prefs.getLong(KEY_LAST_SENT_AT, 0L),
        queued = queue.size()
    )

    /** Queues [event] if usage data is on and the event fits the schema. Never blocks on disk. */
    fun track(event: TelemetryEvent) {
        if (!isActive) return
        if (!TelemetrySchema.accepts(event)) {
            GotchaLog.d(TAG) { "dropped ${event.name}: does not match the schema" }
            return
        }
        val sample = prefs.getFloat(KEY_SAMPLE, 1f).toDouble()
        if (sample < 1.0 && random() >= sample) return
        val queued = QueuedEvent(UUID.randomUUID().toString(), event.name, clock(), sessionId, event.props)
        executor.execute { runCatching { queue.append(queued) } }
    }

    /**
     * Records an uncaught exception straight to the queue, on the crashing
     * thread, since the process is about to die. Class and throwing method only.
     */
    fun recordCrash(thread: Thread, throwable: Throwable, foreground: Boolean) {
        if (!isActive) return
        val event = TelemetryEvents.crash(
            exception = throwable.javaClass.name,
            frame = crashFrame(throwable),
            thread = if (thread === Looper.getMainLooper()?.thread) CrashThread.MAIN else CrashThread.OTHER,
            foreground = foreground
        )
        if (!TelemetrySchema.accepts(event)) return
        runCatching {
            queue.append(QueuedEvent(UUID.randomUUID().toString(), event.name, clock(), sessionId, event.props))
        }
    }

    /** Turns usage data on or off. Off deletes what is queued at once. */
    fun setEnabled(on: Boolean) {
        if (on) {
            val edit = prefs.edit().putBoolean(KEY_ENABLED, true)
            if (installId.isBlank()) edit.putString(KEY_INSTALL_ID, UUID.randomUUID().toString())
            // A server pause from an earlier opt-in does not outlive a fresh one.
            edit.remove(KEY_PAUSED_UNTIL).commit()
            track(TelemetryEvents.optIn(OptInSurface.SETTINGS))
        } else {
            prefs.edit().putBoolean(KEY_ENABLED, false).commit()
            executor.execute { queue.clear() }
        }
    }

    fun setMobileData(on: Boolean) {
        prefs.edit().putBoolean(KEY_MOBILE_DATA, on).apply()
    }

    /**
     * Asks the server to delete everything sent from this phone, then forgets the
     * id and turns usage data off. False if the server could not be reached; the
     * id is kept then, so the user can try again.
     */
    suspend fun deleteMyData(): Boolean = withContext(Dispatchers.IO) {
        val id = installId
        if (id.isBlank()) return@withContext true
        val sent = sender ?: return@withContext false
        val deleted = sent.delete(id)
        if (deleted) {
            prefs.edit()
                .putBoolean(KEY_ENABLED, false)
                .remove(KEY_INSTALL_ID)
                .remove(KEY_LAST_SENT_AT)
                .commit()
            queue.clear()
        }
        deleted
    }

    /** Sends what is queued, if allowed and due. [force] skips the 15-minute spacing. */
    suspend fun flush(force: Boolean = false): FlushOutcome = withContext(Dispatchers.IO) {
        flushLock.withLock { flushLocked(force) }
    }

    @Suppress("ReturnCount", "CyclomaticComplexMethod")
    private fun flushLocked(force: Boolean): FlushOutcome {
        val sent = sender ?: return FlushOutcome.NOT_CONFIGURED
        if (!enabled || installId.isBlank()) return FlushOutcome.OFF
        if (paused()) return FlushOutcome.PAUSED
        val now = clock()
        if (now < prefs.getLong(KEY_RETRY_AFTER, 0L)) return FlushOutcome.NOT_DUE
        val last = prefs.getLong(KEY_LAST_FLUSH_AT, 0L)
        if (!force && now >= last && now - last < MIN_FLUSH_INTERVAL_MS) return FlushOutcome.NOT_DUE
        val net = network()
        if (!net.connected) return FlushOutcome.OFFLINE
        if (net.metered && !prefs.getBoolean(KEY_MOBILE_DATA, false)) return FlushOutcome.METERED
        if (queue.size() == 0) return FlushOutcome.EMPTY
        prefs.edit().putLong(KEY_LAST_FLUSH_AT, now).apply()

        var outcome = FlushOutcome.EMPTY
        repeat(MAX_BATCHES_PER_FLUSH) {
            val batch = batchOf(queue.peek(MAX_EVENTS_PER_BATCH))
            if (batch.isEmpty()) return outcome
            val ids = batch.map { it.id }.toSet()
            when (val result = sent.send(installId, appInfo(), batch, clock())) {
                is SendResult.Accepted -> {
                    queue.remove(ids)
                    val edit = prefs.edit().putLong(KEY_LAST_SENT_AT, clock())
                    result.sample?.let { edit.putFloat(KEY_SAMPLE, it.coerceIn(0.0, 1.0).toFloat()) }
                    if (!result.enabled) {
                        edit.putLong(KEY_PAUSED_UNTIL, clock() + SERVER_PAUSE_MS).apply()
                        queue.clear()
                        return FlushOutcome.PAUSED
                    }
                    edit.apply()
                    outcome = FlushOutcome.SENT
                }
                SendResult.Rejected -> queue.remove(ids)
                SendResult.Unauthorized -> {
                    prefs.edit().putInt(KEY_PAUSED_FOR_VERSION, BuildConfig.VERSION_CODE).apply()
                    queue.clear()
                    return FlushOutcome.PAUSED
                }
                is SendResult.Throttled -> {
                    prefs.edit().putLong(KEY_RETRY_AFTER, clock() + result.retryAfterMs).apply()
                    return FlushOutcome.FAILED
                }
                SendResult.Failed -> return FlushOutcome.FAILED
            }
        }
        return outcome
    }

    /** The oldest events that fit in one request. */
    private fun batchOf(events: List<QueuedEvent>): List<QueuedEvent> {
        var bytes = 0
        return events.takeWhile { event ->
            bytes += event.toJson().toString().length
            bytes <= MAX_BATCH_BYTES
        }
    }

    private fun paused(): Boolean =
        prefs.getInt(KEY_PAUSED_FOR_VERSION, -1) == BuildConfig.VERSION_CODE ||
            clock() < prefs.getLong(KEY_PAUSED_UNTIL, 0L)

    companion object {
        private const val TAG = "Telemetry"
        const val PREFS_NAME = "telemetry"
        internal const val KEY_ENABLED = "enabled"
        internal const val KEY_INSTALL_ID = "install_id"
        internal const val KEY_MOBILE_DATA = "mobile_data"
        internal const val KEY_LAST_FLUSH_AT = "last_flush_at"
        internal const val KEY_LAST_SENT_AT = "last_sent_at"
        internal const val KEY_RETRY_AFTER = "retry_after"
        internal const val KEY_PAUSED_UNTIL = "paused_until"
        internal const val KEY_PAUSED_FOR_VERSION = "paused_for_version"
        internal const val KEY_SAMPLE = "sample"

        const val MIN_FLUSH_INTERVAL_MS = 15L * 60L * 1000L
        const val MAX_EVENTS_PER_BATCH = 50
        const val MAX_BATCH_BYTES = 64 * 1024
        const val MAX_BATCHES_PER_FLUSH = 3

        /** How long the server's enabled:false holds before the app tries again. */
        const val SERVER_PAUSE_MS = 24L * 60L * 60L * 1000L

        /** `Class.method` of the first frame in Gotcha's own code, else of the top frame. */
        internal fun crashFrame(throwable: Throwable): String? {
            val frames = throwable.stackTrace
            val frame = frames.firstOrNull { it.className.startsWith("com.gotcha") } ?: frames.firstOrNull()
            return frame?.let { "${it.className.substringAfterLast('.')}.${it.methodName}" }
        }
    }
}

/**
 * The process-wide [TelemetryClient], installed by [com.gotcha.GotchaApp]. Every
 * call is a no-op before [install], so code that runs in a test or a process
 * without the application class needs no guard.
 */
object Telemetry {

    @Volatile
    private var client: TelemetryClient? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val main by lazy { Handler(Looper.getMainLooper()) }

    /** Foreground bookkeeping, touched only on the main thread. */
    private var coldStartPending = true
    private var sessionStartedAt = 0L
    private var pendingSessionEnd: Runnable? = null

    fun install(context: Context) {
        val app = context.applicationContext
        client = TelemetryClient(
            config = TelemetryConfig(BuildConfig.TELEMETRY_URL.trim(), BuildConfig.TELEMETRY_KEY.trim()),
            prefs = app.getSharedPreferences(TelemetryClient.PREFS_NAME, Context.MODE_PRIVATE),
            queue = TelemetryQueue(File(File(app.filesDir, "telemetry"), "queue.ndjson")),
            sender = TelemetryConfig(BuildConfig.TELEMETRY_URL.trim(), BuildConfig.TELEMETRY_KEY.trim())
                .takeIf { it.configured }
                ?.let { TelemetrySender(it.url, it.key) },
            appInfo = { appInfo(app) },
            network = { networkState(app) }
        )
    }

    /** For tests: replaces the installed client. */
    internal fun installForTest(testClient: TelemetryClient?) {
        client = testClient
    }

    val isConfigured: Boolean get() = client?.isConfigured == true

    fun status(): TelemetryStatus? = client?.status()

    fun track(event: TelemetryEvent) {
        client?.track(event)
    }

    fun setEnabled(on: Boolean) {
        client?.setEnabled(on)
    }

    fun setMobileData(on: Boolean) {
        client?.setMobileData(on)
    }

    suspend fun deleteMyData(): Boolean = client?.deleteMyData() ?: false

    suspend fun flush(force: Boolean = false): FlushOutcome =
        client?.flush(force) ?: FlushOutcome.NOT_CONFIGURED

    /** Flushes in the background without waiting for it. */
    fun flushInBackground(force: Boolean = false) {
        val current = client ?: return
        scope.launch {
            val outcome = runCatching { current.flush(force) }.getOrDefault(FlushOutcome.FAILED)
            GotchaLog.d("Telemetry") { "flush: $outcome" }
        }
    }

    fun recordCrash(thread: Thread, throwable: Throwable, foreground: Boolean) {
        client?.recordCrash(thread, throwable, foreground)
    }

    /**
     * A screen of Gotcha's came to the front with none showing before. Within
     * [SESSION_GAP_MS] of leaving it is the same session, which keeps the
     * assistant's own trips into other apps from splitting one.
     */
    fun onForeground() {
        val c = client ?: return
        pendingSessionEnd?.let {
            main.removeCallbacks(it)
            pendingSessionEnd = null
            return
        }
        c.newSession()
        sessionStartedAt = SystemClock.elapsedRealtime()
        val cold = coldStartPending
        coldStartPending = false
        val startMs = if (cold) SystemClock.uptimeMillis() - Process.getStartUptimeMillis() else null
        c.track(TelemetryEvents.appOpen(cold, startMs))
    }

    /**
     * The last of Gotcha's screens went away; the session ends if it stays away.
     *
     * Sends now, while the app still has the network: from Android 15 a cached
     * app's network is blocked (APP_BACKGROUND), so a flush 30 s later finds
     * none. The session_end recorded then goes out with the next flush — the
     * next start, or the reminder alarm, whose receiver does get the network.
     */
    fun onBackground() {
        val c = client ?: return
        val leftAt = SystemClock.elapsedRealtime()
        flushInBackground()
        val end = Runnable {
            pendingSessionEnd = null
            c.track(TelemetryEvents.sessionEnd(leftAt - sessionStartedAt))
        }
        pendingSessionEnd = end
        main.postDelayed(end, SESSION_GAP_MS)
    }

    private const val SESSION_GAP_MS = 30_000L
    private const val TABLET_MIN_WIDTH_DP = 600

    private fun appInfo(context: Context) = AppInfo(
        version = BuildConfig.VERSION_NAME,
        code = BuildConfig.VERSION_CODE,
        build = BuildConfig.BUILD_TYPE,
        sdk = Build.VERSION.SDK_INT,
        locale = Locale.getDefault().toLanguageTag(),
        deviceClass = if (context.resources.configuration.smallestScreenWidthDp >= TABLET_MIN_WIDTH_DP) {
            "tablet"
        } else {
            "phone"
        }
    )

    private fun networkState(context: Context): NetworkState {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return NetworkState(false, true)
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return NetworkState(false, true)
        return NetworkState(
            connected = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
            metered = !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
        )
    }
}
