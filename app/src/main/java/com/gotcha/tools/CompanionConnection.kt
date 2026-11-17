package com.gotcha.tools

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.DeadObjectException
import android.os.IBinder
import android.util.Log
import com.gotcha.shell.IShellService
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicInteger

/** What the package manager says about the companion app, without binding to it. */
data class CompanionIdentity(
    val installed: Boolean,
    /**
     * The installed `com.gotcha.shell` is signed with the same key as this Gotcha. Anything else
     * holding that package name is an impostor and must never be sent a command or a file.
     */
    val trusted: Boolean,
    val versionName: String?
)

/** Why the companion could not be reached. */
class CompanionUnavailable(val reason: Reason) : Exception(reason.name) {
    enum class Reason { NOT_INSTALLED, UNTRUSTED, BIND_REFUSED, BIND_TIMED_OUT }
}

/** The way [CompanionShellBackend] reaches the companion; an interface so tests can stand in for it. */
interface CompanionLink {
    fun identity(): CompanionIdentity

    /**
     * Runs [block] against the bound service, off the main thread. Binder calls block, and the
     * service's own errors (`IllegalStateException`, `IllegalArgumentException`,
     * `SecurityException`, `RemoteException`) come out of [block] unchanged.
     */
    suspend fun <T> call(block: (IShellService) -> T): T
}

/**
 * The binding to Gotcha Shell's `ShellService`.
 *
 * One per process: a binding is a shared resource, and sub-agents build their own tool
 * executors. It binds on first use, checks the companion's signing certificate before every
 * bind, and lets go again after a quiet spell — the companion keeps itself alive while it has
 * work, so Gotcha has no reason to pin its process in memory.
 */
class CompanionConnection private constructor(private val appContext: Context) : CompanionLink {

    private val bindLock = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val inFlight = AtomicInteger(0)

    @Volatile private var service: IShellService? = null
    private var connection: ServiceConnection? = null
    private var idleUnbind: Job? = null

    override fun identity(): CompanionIdentity = identityOf(appContext)

    override suspend fun <T> call(block: (IShellService) -> T): T = withContext(Dispatchers.IO) {
        inFlight.incrementAndGet()
        try {
            val bound = bind()
            try {
                block(bound)
            } catch (e: DeadObjectException) {
                // The companion process died. Forget the binder so the next call rebinds.
                release()
                throw e
            }
        } finally {
            if (inFlight.decrementAndGet() == 0) scheduleIdleUnbind()
        }
    }

    private suspend fun bind(): IShellService = bindLock.withLock {
        idleUnbind?.cancel()
        service?.takeIf { it.asBinder().isBinderAlive }?.let { return@withLock it }
        unbindLocked()

        val identity = identity()
        if (!identity.installed) throw CompanionUnavailable(CompanionUnavailable.Reason.NOT_INSTALLED)
        if (!identity.trusted) throw CompanionUnavailable(CompanionUnavailable.Reason.UNTRUSTED)

        val ready = CompletableDeferred<IShellService>()
        val pending = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                val bound = IShellService.Stub.asInterface(binder)
                service = bound
                ready.complete(bound)
            }

            override fun onServiceDisconnected(name: ComponentName) {
                service = null
            }

            override fun onBindingDied(name: ComponentName) {
                service = null
            }

            override fun onNullBinding(name: ComponentName) {
                ready.completeExceptionally(CompanionUnavailable(CompanionUnavailable.Reason.BIND_REFUSED))
            }
        }
        // Explicit component: the service has no intent filter, and naming it means nothing
        // else on the device can answer.
        val intent = Intent().setComponent(ComponentName(PACKAGE, SERVICE_CLASS))
        val accepted = try {
            appContext.bindService(intent, pending, Context.BIND_AUTO_CREATE)
        } catch (_: SecurityException) {
            false
        }
        connection = pending
        if (!accepted) {
            Log.w(TAG, "bind refused")
            unbindLocked()
            throw CompanionUnavailable(CompanionUnavailable.Reason.BIND_REFUSED)
        }
        val bound = try {
            withTimeoutOrNull(BIND_TIMEOUT_MS) { ready.await() }
        } catch (e: CompanionUnavailable) {
            unbindLocked()
            throw e
        }
        if (bound == null) {
            Log.w(TAG, "bind timed out after $BIND_TIMEOUT_MS ms")
            unbindLocked()
            throw CompanionUnavailable(CompanionUnavailable.Reason.BIND_TIMED_OUT)
        }
        Log.i(TAG, "bound to Gotcha Shell ${identity.versionName}")
        bound
    }

    private suspend fun release() = bindLock.withLock { unbindLocked() }

    private fun unbindLocked() {
        service = null
        connection?.let { runCatching { appContext.unbindService(it) } }
        connection = null
    }

    private fun scheduleIdleUnbind() {
        idleUnbind?.cancel()
        idleUnbind = scope.launch {
            delay(IDLE_UNBIND_MS)
            bindLock.withLock { if (inFlight.get() == 0) unbindLocked() }
        }
    }

    companion object {
        const val PACKAGE = "com.gotcha.shell"
        private const val TAG = "GotchaShell"
        private const val SERVICE_CLASS = "com.gotcha.shell.ShellService"

        /** A bind to a stopped app on a slow phone takes about a second; this is a generous ceiling. */
        private const val BIND_TIMEOUT_MS = 15_000L

        private const val IDLE_UNBIND_MS = 60_000L

        @Volatile private var instance: CompanionConnection? = null

        fun get(context: Context): CompanionConnection {
            val app = context.applicationContext ?: context
            instance?.takeIf { it.appContext === app }?.let { return it }
            return synchronized(this) {
                instance?.takeIf { it.appContext === app } ?: CompanionConnection(app).also { instance = it }
            }
        }

        fun identityOf(context: Context): CompanionIdentity {
            val pm = context.packageManager
            val info = runCatching { pm.getPackageInfo(PACKAGE, 0) }.getOrNull()
                ?: return CompanionIdentity(installed = false, trusted = false, versionName = null)
            // Both apps are signed with the same key, so "same signer as me" is the whole check:
            // it holds for a release Gotcha with a release companion and for a debug pair, and
            // fails for any other combination.
            val trusted = runCatching {
                pm.checkSignatures(context.packageName, PACKAGE) == PackageManager.SIGNATURE_MATCH
            }.getOrDefault(false)
            return CompanionIdentity(installed = true, trusted = trusted, versionName = info.versionName)
        }
    }
}
