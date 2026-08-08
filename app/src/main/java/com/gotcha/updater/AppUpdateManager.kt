package com.gotcha.updater

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import com.gotcha.BuildConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

@Serializable
data class AppUpdateInfo(
    val versionCode: Int,
    val versionName: String,
    val downloadUrl: String,
    val releaseNotes: String = "",
    val sha256: String? = null
)

sealed interface UpdateStatus {
    data object Idle : UpdateStatus
    data object Checking : UpdateStatus
    data class UpToDate(val currentVersion: String) : UpdateStatus
    data class Available(val info: AppUpdateInfo) : UpdateStatus
    data class Downloading(val progressPercent: Int) : UpdateStatus
    data class ReadyToInstall(val apkFile: File) : UpdateStatus
    data class NeedsInstallPermission(val apkFile: File) : UpdateStatus
    data class Error(val message: String) : UpdateStatus
}

/**
 * In-app updater: reads an update.json manifest, downloads a release APK, and
 * hands it to PackageInstaller.
 *
 * Security model: the manifest and the APK are served over HTTPS from a
 * repository the user controls. [downloadUpdate] refuses any [AppUpdateInfo.downloadUrl]
 * that isn't an https URL on the trusted GitHub releases prefix, and when the
 * manifest pins a [AppUpdateInfo.sha256] the downloaded bytes must match it
 * before the file is offered for install. PackageInstaller additionally refuses
 * to update over an existing install signed with a different key.
 */
class AppUpdateManager(
    private val client: OkHttpClient = defaultClient()
) {
    companion object {
        const val DEFAULT_UPDATE_URL =
            "https://raw.githubusercontent.com/samosa-ai-com/Gotcha/main/update.json"

        const val TRUSTED_DOWNLOAD_HOST = "github.com"
        const val TRUSTED_DOWNLOAD_PATH_PREFIX = "/samosa-ai-com/Gotcha/releases/download/"

        private val jsonParser = Json { ignoreUnknownKeys = true }

        private fun defaultClient(): OkHttpClient {
            return OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .followRedirects(true)
                .followSslRedirects(true)
                .build()
        }

        val shared: AppUpdateManager by lazy { AppUpdateManager() }
    }

    fun parseUpdateJson(jsonString: String): AppUpdateInfo {
        return jsonParser.decodeFromString(AppUpdateInfo.serializer(), jsonString)
    }

    fun isUpdateAvailable(currentVersionCode: Int, remoteInfo: AppUpdateInfo): Boolean {
        return remoteInfo.versionCode > currentVersionCode
    }

    /**
     * Only the manifest-supplied URL is checked (redirects from github.com to the
     * asset CDN are followed by the client and are not under the attacker's control).
     */
    fun isTrustedDownloadUrl(url: String): Boolean = runCatching {
        val uri = java.net.URI(url)
        val host = uri.host ?: return false
        val path = uri.path ?: return false
        uri.scheme.equals("https", ignoreCase = true) &&
            host.equals(TRUSTED_DOWNLOAD_HOST, ignoreCase = true) &&
            path.startsWith(TRUSTED_DOWNLOAD_PATH_PREFIX)
    }.getOrDefault(false)

    suspend fun checkForUpdate(
        currentVersionCode: Int = BuildConfig.VERSION_CODE,
        currentVersionName: String = BuildConfig.VERSION_NAME,
        manifestUrl: String = DEFAULT_UPDATE_URL
    ): UpdateStatus = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url(manifestUrl)
                .build()

            client.awaitResponse(request).use { response ->
                if (!response.isSuccessful) {
                    return@withContext UpdateStatus.Error("Failed to fetch updates (HTTP ${response.code})")
                }
                val bodyText = response.body?.string()
                    ?: return@withContext UpdateStatus.Error("Empty response from update server")

                val remoteInfo = parseUpdateJson(bodyText)
                if (isUpdateAvailable(currentVersionCode, remoteInfo)) {
                    UpdateStatus.Available(remoteInfo)
                } else {
                    UpdateStatus.UpToDate(currentVersionName)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            UpdateStatus.Error(e.message ?: "Unknown error checking for updates")
        }
    }

    suspend fun downloadUpdate(
        context: Context,
        updateInfo: AppUpdateInfo,
        onProgress: (Int) -> Unit = {}
    ): Result<File> {
        if (!isTrustedDownloadUrl(updateInfo.downloadUrl)) {
            return Result.failure(
                IllegalArgumentException("Untrusted download URL: ${updateInfo.downloadUrl}")
            )
        }
        return downloadUpdateTo(File(context.cacheDir, "updates"), updateInfo, onProgress)
    }

    /**
     * Download/verify core, split from [downloadUpdate] so the JVM tests can drive it
     * against a local server. The trusted-URL gate lives on the public entry point.
     * [targetDir] must be a directory the FileProvider maps (see res/xml/file_paths.xml).
     */
    internal suspend fun downloadUpdateTo(
        targetDir: File,
        updateInfo: AppUpdateInfo,
        onProgress: (Int) -> Unit = {}
    ): Result<File> = withContext(Dispatchers.IO) {
        try {
            val dir = targetDir.apply { mkdirs() }
            clearStaleApks(dir)
            val apkFile = File(dir, "gotcha-${updateInfo.versionName}.apk")

            val request = Request.Builder()
                .url(updateInfo.downloadUrl)
                .build()

            client.awaitResponse(request).use { response ->
                if (!response.isSuccessful) {
                    return@withContext Result.failure(
                        RuntimeException("Failed to download APK (HTTP ${response.code})")
                    )
                }

                val body = response.body
                    ?: return@withContext Result.failure(RuntimeException("APK body is null"))

                val contentLength = body.contentLength()
                var lastPercent = -1
                var totalBytesRead = 0L

                body.byteStream().use { input ->
                    FileOutputStream(apkFile).use { output ->
                        val buffer = ByteArray(8192)
                        var bytesRead: Int
                        while (input.read(buffer).also { bytesRead = it } != -1) {
                            output.write(buffer, 0, bytesRead)
                            totalBytesRead += bytesRead
                            if (contentLength > 0) {
                                val percent = ((totalBytesRead * 100) / contentLength).toInt()
                                if (percent != lastPercent) {
                                    lastPercent = percent
                                    onProgress(percent)
                                }
                            }
                        }
                    }
                }
                if (lastPercent != 100) {
                    onProgress(100)
                }

                updateInfo.sha256?.let { expected ->
                    val actual = sha256Hex(apkFile)
                    if (!actual.equals(expected, ignoreCase = true)) {
                        apkFile.delete()
                        return@withContext Result.failure(
                            IllegalStateException("Downloaded APK failed sha256 verification")
                        )
                    }
                }

                Result.success(apkFile)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun canInstall(context: Context): Boolean {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.O ||
            context.packageManager.canRequestPackageInstalls()
    }

    fun openInstallPermissionSettings(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val settingsIntent = Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${context.packageName}")
            ).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(settingsIntent)
        }
    }

    fun installUpdate(context: Context, apkFile: File): Boolean {
        if (!canInstall(context)) {
            openInstallPermissionSettings(context)
            return false
        }

        return runCatching {
            val apkUri: Uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                apkFile
            )

            val installIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(apkUri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            context.startActivity(installIntent)
            true
        }.getOrDefault(false)
    }

    private fun clearStaleApks(dir: File) {
        dir.listFiles { f -> f.isFile && f.extension == "apk" }
            ?.forEach { it.delete() }
    }

    private fun sha256Hex(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(8192)
            var read: Int
            while (input.read(buffer).also { read = it } != -1) {
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private suspend fun OkHttpClient.awaitResponse(request: Request): Response =
        suspendCancellableCoroutine { cont ->
            val call = newCall(request)
            cont.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (cont.isCancelled) return
                    cont.resumeWithException(e)
                }

                override fun onResponse(call: Call, response: Response) {
                    if (cont.isCancelled) {
                        response.close()
                        return
                    }
                    cont.resume(response)
                }
            })
        }
}
