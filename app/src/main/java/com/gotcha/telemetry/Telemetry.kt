package com.gotcha.telemetry

import android.app.Activity
import android.app.ActivityManager
import android.content.Context
import android.content.SharedPreferences
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.view.FrameMetrics
import android.view.Window
import com.gotcha.BuildConfig
import com.gotcha.util.GotchaLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
    /** The server asked for a pause, or refused the enrollment key. */
    val paused: Boolean,
    /** Epoch millis of the last accepted batch, 0 if none. */
    val lastSentAt: Long,
    val queued: Int
)

/** What one flush did, for tests and the debug log. */
enum class FlushOutcome { NOT_CONFIGURED, OFF, PAUSED, NOT_DUE, OFFLINE, METERED, EMPTY, SENT, REJECTED, FAILED }

/**
 * Usage data starts after the first terms decision, and stays off in a build
 * without [TelemetryConfig].
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
    private val registerLock = Mutex()
    private val counters = SessionCounters()

    /** Random per foreground session, never stored. */
    @Volatile
    var sessionId: String = UUID.randomUUID().toString()
        private set

    fun newSession() {
        sessionId = UUID.randomUUID().toString()
        counters.reset(clock())
    }

    val isConfigured: Boolean get() = config.configured && sender != null

    /** True when an event tracked now would be queued. */
    val isActive: Boolean
        get() = isConfigured && enabled && hasConsent && registered && !paused()

    private val enabled: Boolean get() = prefs.getBoolean(KEY_ENABLED, true)
    val hasConsent: Boolean get() = prefs.contains(KEY_CONSENT_AT)
    val region: String? get() = prefs.getString(KEY_REGION, null)
    private val _regionFlow = MutableStateFlow(region)
    val regionFlow: StateFlow<String?> = _regionFlow.asStateFlow()

    /** Usage data is on unless the user turns it off, in every region. */
    val defaultUsageData: Boolean get() = true
    private val registered: Boolean get() = installId.isNotBlank() && token.isNotBlank()
    private val token: String get() = prefs.getString(KEY_TOKEN, "").orEmpty()
    private val installId: String get() = prefs.getString(KEY_INSTALL_ID, "").orEmpty()

    fun status(): TelemetryStatus = TelemetryStatus(
        configured = isConfigured,
        enabled = if (hasConsent) enabled else defaultUsageData,
        mobileData = prefs.getBoolean(KEY_MOBILE_DATA, false),
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
        runCatching { counters.record(event, clock()) }
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

    /** First terms decision, or a later Settings choice. Consent is sent even when declined. */
    fun recordConsent(usageData: Boolean, surface: ConsentSurface) {
        prefs.edit().putLong(KEY_CONSENT_AT, clock()).putBoolean(KEY_ENABLED, usageData).apply()
        val event = TelemetryEvents.consent(usageData, surface)
        if (isConfigured && TelemetrySchema.accepts(event)) {
            runCatching {
                if (!usageData) queue.retainConsent()
                queue.append(QueuedEvent(UUID.randomUUID().toString(), event.name, clock(), sessionId, event.props))
            }.onFailure {
                GotchaLog.w(TAG) { "consent queue failed: ${it.javaClass.simpleName}" }
            }
        }
    }

    /** Records a Settings choice, including a toggle before the first terms decision. */
    fun setEnabled(on: Boolean, afterRecorded: () -> Unit = {}) {
        prefs.edit().putLong(KEY_CONSENT_AT, clock()).putBoolean(KEY_ENABLED, on)
            .remove(KEY_PAUSED_UNTIL).apply()
        executor.execute {
            runCatching {
                if (!on) queue.retainConsent()
                if (isConfigured) {
                    val event = TelemetryEvents.consent(on, ConsentSurface.SETTINGS)
                    if (TelemetrySchema.accepts(event)) {
                        queue.append(
                            QueuedEvent(
                                UUID.randomUUID().toString(),
                                event.name,
                                clock(),
                                sessionId,
                                event.props
                            )
                        )
                    }
                }
            }.onFailure { GotchaLog.w(TAG) { "consent queue failed: ${it.javaClass.simpleName}" } }
            runCatching { afterRecorded() }
        }
    }

    /** Registration bypasses the usage switch and can be retried after a network failure. */
    suspend fun ensureRegistered(): Boolean = withContext(Dispatchers.IO) {
        registerLock.withLock {
            if (registered) return@withLock true
            val sent = sender ?: return@withLock false
            val installedAt = prefs.getLong(KEY_INSTALLED_AT, 0L).takeIf { it > 0L }
                ?: clock().also { prefs.edit().putLong(KEY_INSTALLED_AT, it).apply() }
            when (val result = runCatching { sent.register(appInfo(), installedAt) }.getOrNull()) {
                is RegisterResult.Registered -> {
                    prefs.edit().putString(KEY_INSTALL_ID, result.installId)
                        .putString(KEY_TOKEN, result.token).putString(KEY_REGION, result.region)
                        .putBoolean(KEY_CONSENT_REQUIRED, result.consentRequired)
                        .putFloat(KEY_SAMPLE, (result.sample ?: 1.0).coerceIn(0.0, 1.0).toFloat())
                        .remove(KEY_REREG_ATTEMPTS).apply()
                    _regionFlow.value = result.region
                    if (!result.enabled) {
                        prefs.edit().putLong(KEY_PAUSED_UNTIL, clock() + SERVER_PAUSE_MS).apply()
                    }
                    GotchaLog.i(TAG) { "registration: REGISTERED" }
                    true
                }
                RegisterResult.Refused -> {
                    prefs.edit().putInt(KEY_PAUSED_FOR_VERSION, BuildConfig.VERSION_CODE).apply()
                    GotchaLog.w(TAG) { "registration: enrollment_key_refused" }
                    false
                }
                else -> {
                    val attempts = prefs.getInt(KEY_REREG_ATTEMPTS, 0)
                    if (attempts > 0) {
                        val next = attempts + 1
                        val edit = prefs.edit().putInt(KEY_REREG_ATTEMPTS, next)
                        if (next >= MAX_REREG_ATTEMPTS) {
                            edit.putInt(KEY_PAUSED_FOR_VERSION, BuildConfig.VERSION_CODE)
                        }
                        edit.apply()
                    }
                    GotchaLog.w(TAG) { "registration: FAILED" }
                    false
                }
            }
        }
    }

    fun addFrame(janky: Boolean) { runCatching { counters.addFrames(if (janky) 1 else 0, 1) } }
    fun setPreviousAnr(value: Boolean) { runCatching { counters.setAnr(value) } }

    fun finishSession(lengthMs: Long) {
        if (!isActive) return
        runCatching { track(SessionRules.profileOf(counters.snapshot(), lengthMs)) }.onFailure {
            GotchaLog.w(TAG) { "session_profile failed: ${it.javaClass.simpleName}" }
        }
    }

    fun setMobileData(on: Boolean) {
        prefs.edit().putBoolean(KEY_MOBILE_DATA, on).apply()
    }

    /** Sends what is queued, if allowed and due. [force] skips the 15-minute spacing. */
    suspend fun flush(force: Boolean = false): FlushOutcome = withContext(Dispatchers.IO) {
        val result = runCatching { flushLock.withLock { flushLocked(force) } }
        val outcome = result.getOrDefault(FlushOutcome.FAILED)
        GotchaLog.i(TAG) {
            "flush: $outcome" + (result.exceptionOrNull()?.let { " reason=${it.javaClass.simpleName}" } ?: "")
        }
        outcome
    }

    @Suppress("ReturnCount", "CyclomaticComplexMethod")
    private suspend fun flushLocked(force: Boolean): FlushOutcome {
        val sent = sender ?: return FlushOutcome.NOT_CONFIGURED
        if (paused()) return FlushOutcome.PAUSED
        if (!registered) {
            if (prefs.getInt(KEY_REREG_ATTEMPTS, 0) == 0) return FlushOutcome.OFF
            if (!ensureRegistered()) return if (paused()) FlushOutcome.PAUSED else FlushOutcome.FAILED
        }
        if (!enabled && queue.peek(1).none { it.name == TelemetryEvents.CONSENT }) return FlushOutcome.OFF
        if (!hasConsent && queue.peek(1).none { it.name == TelemetryEvents.CONSENT }) return FlushOutcome.OFF
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
            when (val result = sent.send(installId, token, appInfo(), batch, clock())) {
                is SendResult.Accepted -> {
                    queue.remove(ids)
                    val edit = prefs.edit().putLong(KEY_LAST_SENT_AT, clock())
                    result.sample?.let { edit.putFloat(KEY_SAMPLE, it.coerceIn(0.0, 1.0).toFloat()) }
                    if (!result.enabled) {
                        edit.putLong(KEY_PAUSED_UNTIL, clock() + SERVER_PAUSE_MS).apply()
                        queue.retainConsent()
                        return FlushOutcome.PAUSED
                    }
                    edit.apply()
                    outcome = FlushOutcome.SENT
                }
                SendResult.Rejected -> {
                    GotchaLog.w(TAG) {
                        "flush failure: permanent_batch_rejection; dropping ${ids.size} events including consent"
                    }
                    queue.remove(ids)
                    return FlushOutcome.REJECTED
                }
                SendResult.Unauthorized -> {
                    GotchaLog.w(TAG) { "flush failure: token_unauthorized" }
                    prefs.edit().remove(KEY_INSTALL_ID).remove(KEY_TOKEN)
                        .putInt(KEY_REREG_ATTEMPTS, 1).apply()
                    return if (ensureRegistered()) {
                        FlushOutcome.FAILED
                    } else if (paused()) {
                        FlushOutcome.PAUSED
                    } else {
                        FlushOutcome.FAILED
                    }
                }
                is SendResult.Throttled -> {
                    GotchaLog.w(TAG) { "flush failure: rate_limited" }
                    prefs.edit().putLong(KEY_RETRY_AFTER, clock() + result.retryAfterMs).apply()
                    return FlushOutcome.FAILED
                }
                SendResult.Failed -> {
                    GotchaLog.w(TAG) { "flush failure: network_or_server" }
                    return FlushOutcome.FAILED
                }
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
        internal const val KEY_CONSENT_AT = "consent_at"
        internal const val KEY_INSTALLED_AT = "installed_at"
        internal const val KEY_TOKEN = "token"
        internal const val KEY_REGION = "region"
        internal const val KEY_CONSENT_REQUIRED = "consent_required"
        internal const val KEY_INSTALL_ID = "install_id"
        internal const val KEY_MOBILE_DATA = "mobile_data"
        internal const val KEY_LAST_FLUSH_AT = "last_flush_at"
        internal const val KEY_LAST_SENT_AT = "last_sent_at"
        internal const val KEY_RETRY_AFTER = "retry_after"
        internal const val KEY_PAUSED_UNTIL = "paused_until"
        internal const val KEY_PAUSED_FOR_VERSION = "paused_for_version"
        internal const val KEY_SAMPLE = "sample"
        internal const val KEY_REREG_ATTEMPTS = "rereg_attempts"

        const val MIN_FLUSH_INTERVAL_MS = 15L * 60L * 1000L
        const val MAX_EVENTS_PER_BATCH = 50
        const val MAX_BATCH_BYTES = 64 * 1024
        const val MAX_BATCHES_PER_FLUSH = 3
        private const val MAX_REREG_ATTEMPTS = 3

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
@Suppress("TooManyFunctions")
object Telemetry {

    @Volatile
    private var client: TelemetryClient? = null
    private val unknownRegion = MutableStateFlow<String?>(null).asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val main by lazy { Handler(Looper.getMainLooper()) }

    /** Foreground bookkeeping, touched only on the main thread. */
    private var coldStartPending = true
    private var sessionStartedAt = 0L
    private var pendingSessionEnd: Runnable? = null
    private val frameThread by lazy { HandlerThread("telemetry-frames").apply { start() } }
    private val frameListeners = mutableMapOf<Activity, Window.OnFrameMetricsAvailableListener>()
    private var appContext: Context? = null

    @Volatile
    private var permissionWatch: PermissionWatch? = null

    fun install(context: Context) {
        val app = context.applicationContext
        appContext = app
        val prefs = app.getSharedPreferences(TelemetryClient.PREFS_NAME, Context.MODE_PRIVATE)
        permissionWatch = PermissionWatch(prefs) { PermissionWatch.grantedOn(app) }
        client = TelemetryClient(
            config = TelemetryConfig(BuildConfig.TELEMETRY_URL.trim(), BuildConfig.TELEMETRY_KEY.trim()),
            prefs = prefs,
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
    val region: String? get() = client?.region
    val regionFlow: StateFlow<String?> get() = client?.regionFlow ?: unknownRegion
    val defaultUsageData: Boolean get() = client?.defaultUsageData == true
    val hasConsent: Boolean get() = client?.hasConsent == true

    fun ensureRegistered() {
        val current = client ?: return
        scope.launch { runCatching { current.ensureRegistered() } }
    }

    fun recordConsent(usageData: Boolean, surface: ConsentSurface) {
        val current = client ?: return
        scope.launch {
            runCatching {
                current.recordConsent(usageData, surface)
                current.ensureRegistered()
                current.flush(force = true)
            }
        }
    }

    fun status(): TelemetryStatus? = client?.status()

    fun track(event: TelemetryEvent) {
        client?.track(event)
    }

    fun setEnabled(on: Boolean) {
        val current = client
        if (current != null) {
            current.setEnabled(on) {
                scope.launch {
                    runCatching {
                        current.ensureRegistered()
                        current.flush(force = true)
                    }
                }
            }
        }
        if (on) checkPermissionsInBackground() else permissionWatch?.reset()
    }

    fun setMobileData(on: Boolean) {
        client?.setMobileData(on)
    }

    /**
     * Compares the permissions granted now with the last look and records the
     * changes. Only while collecting: a change made with usage data off is not
     * recorded later, because turning it on starts from what is granted then.
     */
    private fun checkPermissions() {
        val c = client ?: return
        val watch = permissionWatch ?: return
        if (!c.isActive) return
        watch.changes().forEach { c.track(it) }
    }

    private fun checkPermissionsInBackground() {
        scope.launch { runCatching { checkPermissions() } }
    }

    suspend fun flush(force: Boolean = false): FlushOutcome =
        client?.flush(force) ?: FlushOutcome.NOT_CONFIGURED.also {
            GotchaLog.i("Telemetry") { "flush: $it" }
        }

    /** Flushes in the background without waiting for it. */
    fun flushInBackground(force: Boolean = false) {
        val current = client ?: run {
            GotchaLog.i("Telemetry") { "flush: ${FlushOutcome.NOT_CONFIGURED}" }
            return
        }
        scope.launch {
            runCatching { current.ensureRegistered() }
            runCatching { current.flush(force) }.onFailure {
                GotchaLog.w("Telemetry") { "flush: FAILED reason=${it.javaClass.simpleName}" }
            }
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
        // Before anything else: back from Android Settings is where most grants happen.
        checkPermissionsInBackground()
        pendingSessionEnd?.let {
            main.removeCallbacks(it)
            pendingSessionEnd = null
            return
        }
        c.newSession()
        val cold = coldStartPending
        if (cold) scope.launch { runCatching { readPreviousAnr() }.onSuccess { c.setPreviousAnr(it) } }
        sessionStartedAt = SystemClock.elapsedRealtime()
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
        scope.launch {
            // A grant from a system dialog on the way out goes in this batch.
            runCatching { checkPermissions() }
            runCatching { c.flush() }.onFailure {
                GotchaLog.w("Telemetry") { "flush: FAILED reason=${it.javaClass.simpleName}" }
            }
        }
        val end = Runnable {
            pendingSessionEnd = null
            val length = leftAt - sessionStartedAt
            c.track(TelemetryEvents.sessionEnd(length))
            scope.launch { runCatching { c.finishSession(length) } }
        }
        pendingSessionEnd = end
        main.postDelayed(end, SESSION_GAP_MS)
    }

    fun observeActivity(activity: Activity) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return
        runCatching {
            val listener = Window.OnFrameMetricsAvailableListener { _, metrics, _ ->
                runCatching {
                    val total = metrics.getMetric(FrameMetrics.TOTAL_DURATION)
                    client?.addFrame(total > 16_666_667L)
                }
            }
            activity.window.addOnFrameMetricsAvailableListener(listener, Handler(frameThread.looper))
            frameListeners[activity] = listener
        }
    }

    fun stopObservingActivity(activity: Activity) {
        val listener = frameListeners.remove(activity) ?: return
        runCatching { activity.window.removeOnFrameMetricsAvailableListener(listener) }
    }

    private fun readPreviousAnr(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return false
        val app = appContext ?: return false
        val manager = app.getSystemService(ActivityManager::class.java) ?: return false
        return manager.getHistoricalProcessExitReasons(null, 0, 1)
            .firstOrNull()?.reason == android.app.ApplicationExitInfo.REASON_ANR
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
        },
        installProfile = installProfile(context)
    )

    @Suppress("MagicNumber", "CyclomaticComplexMethod")
    private fun installProfile(context: Context): InstallProfile {
        fun storageBucket(bytes: Long): String = when {
            bytes < 4L * 1024 * 1024 * 1024 -> "<4gb"
            bytes < 8L * 1024 * 1024 * 1024 -> "4-8gb"
            bytes < 16L * 1024 * 1024 * 1024 -> "8-16gb"
            bytes < 32L * 1024 * 1024 * 1024 -> "16-32gb"
            bytes < 64L * 1024 * 1024 * 1024 -> "32-64gb"
            bytes < 128L * 1024 * 1024 * 1024 -> "64-128gb"
            else -> "128gb+"
        }
        val ram = runCatching {
            val memory = ActivityManager.MemoryInfo()
            context.getSystemService(ActivityManager::class.java)?.getMemoryInfo(memory)
            storageBucket(memory.totalMem)
        }.getOrDefault("unknown")
        val storage = runCatching { storageBucket(android.os.StatFs(context.filesDir.path).totalBytes) }
            .getOrDefault("unknown")
        val width = context.resources.configuration.screenWidthDp
        val screen = when {
            width <= 0 -> "unknown"
            width < 360 -> "<360"
            width < 412 -> "360-411"
            width < 600 -> "412-599"
            else -> "600+"
        }
        val density = when (context.resources.displayMetrics.densityDpi) {
            in 1..140 -> "ldpi"
            in 141..200 -> "mdpi"
            in 201..280 -> "hdpi"
            in 281..400 -> "xhdpi"
            in 401..560 -> "xxhdpi"
            else -> "xxxhdpi"
        }
        val refreshRate = runCatching {
            context.getSystemService(android.hardware.display.DisplayManager::class.java)
                ?.getDisplay(0)?.refreshRate ?: 0f
        }.getOrDefault(0f)
        val refresh = when {
            refreshRate <= 0 -> "unknown"
            refreshRate < 75 -> "60"
            refreshRate < 105 -> "90"
            else -> "120+"
        }
        val manufacturer = Build.MANUFACTURER.orEmpty().lowercase(Locale.ROOT)
        val skin = when {
            manufacturer.contains("samsung") -> "oneui"
            manufacturer.contains("xiaomi") -> "miui"
            manufacturer.contains("google") -> "pixel"
            else -> "other"
        }
        val source = runCatching {
            val installer = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                context.packageManager.getInstallSourceInfo(context.packageName).installingPackageName
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getInstallerPackageName(context.packageName)
            }
            if (installer == "com.android.vending") "play" else "sideload"
        }.getOrDefault("sideload")
        return InstallProfile(
            source = source,
            oemSkin = skin,
            ram = ram,
            storage = storage,
            screenDp = screen,
            density = density,
            refresh = refresh,
            rooted = Build.TAGS.orEmpty().contains("test-keys")
        )
    }

    private fun networkState(context: Context): NetworkState {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return NetworkState(false, true)
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return NetworkState(false, true)
        return NetworkState(
            connected = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
            metered = !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
        )
    }
}
