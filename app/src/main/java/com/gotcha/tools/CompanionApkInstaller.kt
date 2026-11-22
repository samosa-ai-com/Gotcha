package com.gotcha.tools

import android.app.PendingIntent
import android.content.Context
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Build
import com.gotcha.auth.ShellApkRelease
import com.gotcha.updater.AppUpdateManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.security.MessageDigest

/**
 * Downloads, verifies and installs the Gotcha Shell companion APK.
 *
 * The order here is the security property, and it is deliberate: **nothing reaches the installer
 * before it has been verified**, and the strongest check is last. A digest proves the bytes
 * arrived intact; only the signature proves *who made them*, and a server can choose where bytes
 * come from but never which key signed them.
 *
 * Failure is reported as a typed result rather than an exception so the screen can say something
 * specific — "your plan does not include this" and "the download was corrupted" need different
 * words and different next steps.
 */
internal class CompanionApkInstaller(private val context: Context) {

    sealed interface Result {
        /** Handed to the system installer; the outcome arrives via [InstallReceiver]. */
        data object Started : Result

        /** The user must allow installing from Gotcha first. */
        data object NeedInstallPermission : Result

        /** The tier gate refused; do not retry, this is an answer. */
        data object NotInPlan : Result

        /** A link expired mid-flight. Fetching a fresh one may succeed. */
        data object Expired : Result

        /** The bytes did not match the digest or size the server named. */
        data object Corrupt : Result

        /** The APK is not signed by the same key as this app. */
        data object UntrustedSigner : Result

        /** The APK's package is not the companion, or it would be a downgrade. */
        data object WrongPackage : Result

        /** Anything else: network, disk, installer refusal. */
        data class Failed(val message: String) : Result
    }

    /** Where the download lands. NOT the updater's directory: its cleanup deletes every .apk. */
    private fun scratchDir(): File = File(context.cacheDir, "shell-apk").apply { mkdirs() }

    private fun client(): OkHttpClient = OkHttpClient.Builder()
        // The download is one bounded GET with a checksummed body; redirects and extra logging
        // are both things we do not want on a request carrying a session token.
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    /**
     * Downloads, verifies and installs, reporting each phase.
     *
     * The progress callback is what stops this looking frozen: the APK is several megabytes and
     * the download is the longest step, but it previously reported nothing at all, so the screen
     * showed no bar and a user reasonably concluded it had hung.
     *
     * Phases match the companion's runtime phases so one progress bar serves both:
     * `download-apk` carries real byte counts, `verify-apk` and `install-apk` are indeterminate.
     */
    suspend fun install(
        release: ShellApkRelease,
        baseUrl: String,
        bearer: String,
        onProgress: (phase: String, done: Long, total: Long) -> Unit = { _, _, _ -> }
    ): Result {
        // canInstall/openInstallPermissionSettings are instance methods of the updater.
        if (!AppUpdateManager().canInstall(context)) return Result.NeedInstallPermission

        val apk = withContext(Dispatchers.IO) { download(release, baseUrl, bearer, onProgress) }
        if (apk is Download.Outcome.Failed) return apk.result

        val file = (apk as Download.Outcome.Done).file
        try {
            onProgress(PHASE_VERIFY, 0, 0)
            verify(file, release)?.let { return it }
            onProgress(PHASE_INSTALL, 0, 0)
            return startInstall(file)
        } finally {
            // The installer has copied what it needs once started; a failure leaves nothing.
            file.delete()
        }
    }

    /** Fetches the bytes. The path is resolved against the configured origin, never the answer. */
    private fun download(
        release: ShellApkRelease,
        baseUrl: String,
        bearer: String,
        onProgress: (phase: String, done: Long, total: Long) -> Unit
    ): Download.Outcome {
        val url = baseUrl.trimEnd('/') + release.downloadPath
        val target = File(scratchDir(), "companion.apk")
        val request = Request.Builder().url(url).header("Authorization", bearer).get().build()
        return try {
            client().newCall(request).execute().use { response ->
                if (response.code == 403) return Download.Outcome.Failed(Result.NotInPlan)
                if (response.code == 404) return Download.Outcome.Failed(Result.Expired)
                if (!response.isSuccessful) {
                    return Download.Outcome.Failed(Result.Failed("Download failed (${response.code})"))
                }
                val body = response.body ?: return Download.Outcome.Failed(Result.Failed("Empty response"))
                // Prefer the server's length; fall back to the advertised size when absent.
                val total = body.contentLength().takeIf { it > 0 } ?: release.sizeBytes
                streamToFile(body.byteStream(), target, total, onProgress)
            }
            Download.Outcome.Done(target)
        } catch (e: Exception) {
            Download.Outcome.Failed(Result.Failed(e.message ?: "Download failed"))
        }
    }

    /** Streams the body to *target*, reporting coarse progress so the bar moves. */
    private fun streamToFile(
        input: java.io.InputStream,
        target: File,
        total: Long,
        onProgress: (phase: String, done: Long, total: Long) -> Unit
    ) {
        var done = 0L
        onProgress(PHASE_DOWNLOAD, 0, total)
        target.outputStream().use { out ->
            input.use { source ->
                val buffer = ByteArray(64 * 1024)
                var read = source.read(buffer)
                while (read > 0) {
                    out.write(buffer, 0, read)
                    done += read
                    // A callback per 64 KiB is thousands of recompositions for no benefit; the
                    // final chunk still reports the exact total.
                    if (done % (256 * 1024) < 64 * 1024) onProgress(PHASE_DOWNLOAD, done, total)
                    read = source.read(buffer)
                }
            }
        }
        onProgress(PHASE_DOWNLOAD, done, total)
    }

    /**
     * Every check that can be made without waking the installer. Returns null when the file is
     * acceptable, or the specific reason it is not.
     */
    private fun verify(file: File, release: ShellApkRelease): Result? {
        if (!file.exists() || file.length() != release.sizeBytes) return Result.Corrupt
        if (sha256Hex(file) != release.sha256) return Result.Corrupt

        val pm = context.packageManager
        val archive = pm.getPackageArchiveInfo(
            file.absolutePath,
            PackageManager.GET_SIGNING_CERTIFICATES
        ) ?: return Result.Corrupt

        if (archive.packageName != CompanionConnection.PACKAGE) return Result.WrongPackage

        // Downgrade protection: Android enforces it on upgrade too, but saying so here gives a
        // legible message instead of a generic installer failure.
        val installed = runCatching { pm.getPackageInfo(CompanionConnection.PACKAGE, 0) }.getOrNull()
        if (installed != null && archive.longVersionCode < installed.longVersionCode) {
            return Result.WrongPackage
        }

        // The decisive check: the archive must be signed by the same key as this app. This is
        // what stops a substituted APK, and it is why no digest pin is needed here.
        val archiveSigners = signerDigests(archive)
        val selfSigners = runCatching {
            pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        }.getOrNull()?.let { signerDigests(it) } ?: emptySet()
        if (archiveSigners.isEmpty() || archiveSigners.intersect(selfSigners).isEmpty()) {
            return Result.UntrustedSigner
        }
        return null
    }

    @Suppress("DEPRECATION")
    private fun signerDigests(info: android.content.pm.PackageInfo): Set<String> {
        val digest = MessageDigest.getInstance("SHA-256")
        val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val signing = info.signingInfo ?: return emptySet()
            if (signing.hasMultipleSigners()) signing.apkContentsSigners else signing.signingCertificateHistory
        } else {
            info.signatures
        }
        return signatures.orEmpty().map { sig ->
            digest.reset()
            digest.digest(sig.toByteArray()).joinToString("") { "%02x".format(it) }
        }.toSet()
    }

    private fun startInstall(file: File): Result {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
            .apply {
                setAppPackageName(CompanionConnection.PACKAGE)
                setSize(file.length())
            }
        return try {
            val sessionId = installer.createSession(params)
            installer.openSession(sessionId).use { session ->
                file.inputStream().use { input ->
                    session.openWrite("companion", 0, file.length()).use { out ->
                        input.copyTo(out)
                        session.fsync(out)
                    }
                }
                session.commit(InstallReceiver.statusIntent(context, sessionId).intentSender)
            }
            Result.Started
        } catch (e: Exception) {
            Result.Failed(e.message ?: "Could not start the install")
        }
    }

    private fun sha256Hex(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(8192)
            var read: Int
            while (input.read(buffer).also { read = it } != -1) digest.update(buffer, 0, read)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private object Download {
        sealed interface Outcome {
            class Done(val file: File) : Outcome
            class Failed(val result: Result) : Outcome
        }
    }

    /** Sends the user to the per-app "install unknown apps" switch. */
    fun openInstallPermissionSettings(context: Context) =
        AppUpdateManager().openInstallPermissionSettings(context)

    companion object {
        /** Phase names, matching the companion's runtime phases so one bar serves both. */
        const val PHASE_DOWNLOAD = "download-apk"
        const val PHASE_VERIFY = "verify-apk"
        const val PHASE_INSTALL = "install-apk"

        /** Mutable: API 31+ requires an explicit flag, and the system fills the extras in. */
        internal fun pendingFlags(): Int =
            PendingIntent.FLAG_UPDATE_CURRENT or
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
    }
}
