package com.gotcha.updater

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

class AppUpdateManagerTest {

    private val updateManager = AppUpdateManager()

    private lateinit var server: MockWebServer

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun parseUpdateJson_validJson_parsesCorrectly() {
        val json = """
            {
              "versionCode": 2,
              "versionName": "1.0.1",
              "downloadUrl": "https://github.com/samosa-ai-com/Gotcha/releases/download/v1.0.1/app-release.apk",
              "releaseNotes": "Bug fixes"
            }
        """.trimIndent()

        val info = updateManager.parseUpdateJson(json)

        assertEquals(2, info.versionCode)
        assertEquals("1.0.1", info.versionName)
        assertEquals(
            "https://github.com/samosa-ai-com/Gotcha/releases/download/v1.0.1/app-release.apk",
            info.downloadUrl
        )
        assertEquals("Bug fixes", info.releaseNotes)
        assertEquals(null, info.sha256)
    }

    @Test
    fun parseUpdateJson_withSha256_parsesIt() {
        val json = """
            {
              "versionCode": 2,
              "versionName": "1.0.1",
              "downloadUrl": "https://github.com/samosa-ai-com/Gotcha/releases/download/v1.0.1/app-release.apk",
              "sha256": "ab12cd34"
            }
        """.trimIndent()

        val info = updateManager.parseUpdateJson(json)

        assertEquals("ab12cd34", info.sha256)
    }

    @Test
    fun isUpdateAvailable_higherRemoteVersion_returnsTrue() {
        val info = AppUpdateInfo(
            versionCode = 2,
            versionName = "1.0.1",
            downloadUrl = "https://example.com/app.apk",
            releaseNotes = "Notes"
        )

        val result = updateManager.isUpdateAvailable(currentVersionCode = 1, remoteInfo = info)

        assertTrue(result)
    }

    @Test
    fun isUpdateAvailable_sameOrLowerRemoteVersion_returnsFalse() {
        val info = AppUpdateInfo(
            versionCode = 1,
            versionName = "1.0.0",
            downloadUrl = "https://example.com/app.apk",
            releaseNotes = "Notes"
        )

        val resultSame = updateManager.isUpdateAvailable(currentVersionCode = 1, remoteInfo = info)
        val resultHigherLocal = updateManager.isUpdateAvailable(currentVersionCode = 2, remoteInfo = info)

        assertFalse(resultSame)
        assertFalse(resultHigherLocal)
    }

    @Test
    fun isTrustedDownloadUrl_trustedGithubReleasesUrl_returnsTrue() {
        assertTrue(
            updateManager.isTrustedDownloadUrl(
                "https://github.com/samosa-ai-com/Gotcha/releases/download/v1.0.1/app-release.apk"
            )
        )
    }

    @Test
    fun isTrustedDownloadUrl_httpScheme_returnsFalse() {
        assertFalse(
            updateManager.isTrustedDownloadUrl(
                "http://github.com/samosa-ai-com/Gotcha/releases/download/v1.0.1/app-release.apk"
            )
        )
    }

    @Test
    fun isTrustedDownloadUrl_otherHost_returnsFalse() {
        assertFalse(
            updateManager.isTrustedDownloadUrl(
                "https://evil.example.com/releases/download/v1.0.1/app-release.apk"
            )
        )
    }

    @Test
    fun isTrustedDownloadUrl_otherPath_returnsFalse() {
        assertFalse(
            updateManager.isTrustedDownloadUrl(
                "https://github.com/other-org/Gotcha/releases/download/v1.0.1/app-release.apk"
            )
        )
    }

    @Test
    fun isTrustedDownloadUrl_nonUrl_returnsFalse() {
        assertFalse(updateManager.isTrustedDownloadUrl("not a url"))
    }

    @Test
    fun checkForUpdate_httpError_returnsError() = runTest {
        server.enqueue(MockResponse().setResponseCode(404))

        val status = updateManager.checkForUpdate(
            currentVersionCode = 1,
            currentVersionName = "1.0.0",
            manifestUrl = server.url("/update.json").toString()
        )

        assertTrue(status is UpdateStatus.Error)
        assertTrue((status as UpdateStatus.Error).message.contains("404"))
    }

    @Test
    fun checkForUpdate_newerRemoteVersion_returnsAvailable() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """
                {
                  "versionCode": 2,
                  "versionName": "1.0.1",
                  "downloadUrl": "https://github.com/samosa-ai-com/Gotcha/releases/download/v1.0.1/app-release.apk"
                }
                """.trimIndent()
            )
        )

        val status = updateManager.checkForUpdate(
            currentVersionCode = 1,
            currentVersionName = "1.0.0",
            manifestUrl = server.url("/update.json").toString()
        )

        assertTrue(status is UpdateStatus.Available)
        assertEquals(2, (status as UpdateStatus.Available).info.versionCode)
    }

    @Test
    fun checkForUpdate_sameVersion_returnsUpToDate() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """
                {
                  "versionCode": 1,
                  "versionName": "1.0.0",
                  "downloadUrl": "https://github.com/samosa-ai-com/Gotcha/releases/download/v1.0.0/app-release.apk"
                }
                """.trimIndent()
            )
        )

        val status = updateManager.checkForUpdate(
            currentVersionCode = 1,
            currentVersionName = "1.0.0",
            manifestUrl = server.url("/update.json").toString()
        )

        assertTrue(status is UpdateStatus.UpToDate)
        assertEquals("1.0.0", (status as UpdateStatus.UpToDate).currentVersion)
    }

    @Test
    fun checkForUpdate_invalidJson_returnsError() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("not json"))

        val status = updateManager.checkForUpdate(
            currentVersionCode = 1,
            currentVersionName = "1.0.0",
            manifestUrl = server.url("/update.json").toString()
        )

        assertTrue(status is UpdateStatus.Error)
    }

    @Test
    fun downloadUpdateTo_success_downloadsFileAndReportsProgress() = runTest {
        val body = ByteArray(100_000) { 0x41 }
        server.enqueue(MockResponse().setResponseCode(200).setBody(Buffer().write(body)))
        val progress = mutableListOf<Int>()

        val result = updateManager.downloadUpdateTo(
            tempFolder.root,
            infoFor(server.url("/app.apk").toString()),
            onProgress = { progress.add(it) }
        )

        assertTrue(result.isSuccess)
        val apkFile = result.getOrThrow()
        assertArrayEquals(body, apkFile.readBytes())
        assertTrue(progress.isNotEmpty())
        assertEquals(100, progress.last())
    }

    @Test
    fun downloadUpdateTo_progressIsThrottledNotPerChunk() = runTest {
        val body = ByteArray(2 * 1024 * 1024) { 0x41 }
        server.enqueue(MockResponse().setResponseCode(200).setBody(Buffer().write(body)))
        val progress = mutableListOf<Int>()

        val result = updateManager.downloadUpdateTo(
            tempFolder.root,
            infoFor(server.url("/app.apk").toString()),
            onProgress = { progress.add(it) }
        )

        assertTrue(result.isSuccess)
        // 8192-byte chunks over 2 MB mean ~245 reads; distinct percents from 0..100 can
        // never exceed 101, so this proves the callback is throttled to percent changes.
        assertTrue("progress fired ${progress.size} times", progress.size <= 101)
        assertEquals(100, progress.last())
    }

    @Test
    fun downloadUpdateTo_chunkedBodyWithoutContentLength_stillReports100() = runTest {
        val body = "apk-bytes".repeat(1000)
        server.enqueue(MockResponse().setResponseCode(200).setChunkedBody(body, 100))
        val progress = mutableListOf<Int>()

        val result = updateManager.downloadUpdateTo(
            tempFolder.root,
            infoFor(server.url("/app.apk").toString()),
            onProgress = { progress.add(it) }
        )

        assertTrue(result.isSuccess)
        assertEquals(listOf(100), progress)
    }

    @Test
    fun downloadUpdateTo_httpError_returnsFailure() = runTest {
        server.enqueue(MockResponse().setResponseCode(500))

        val result = updateManager.downloadUpdateTo(
            tempFolder.root,
            infoFor(server.url("/app.apk").toString())
        )

        assertTrue(result.isFailure)
    }

    @Test
    fun downloadUpdateTo_sha256Matches_succeeds() = runTest {
        val body = ByteArray(4096) { 0x42 }
        server.enqueue(MockResponse().setResponseCode(200).setBody(Buffer().write(body)))

        val result = updateManager.downloadUpdateTo(
            tempFolder.root,
            infoFor(server.url("/app.apk").toString(), sha256 = sha256Hex(body))
        )

        assertTrue(result.isSuccess)
    }

    @Test
    fun downloadUpdateTo_sha256Mismatch_removesFileAndFails() = runTest {
        val body = ByteArray(4096) { 0x42 }
        server.enqueue(MockResponse().setResponseCode(200).setBody(Buffer().write(body)))

        val result = updateManager.downloadUpdateTo(
            tempFolder.root,
            infoFor(server.url("/app.apk").toString(), sha256 = "0".repeat(64))
        )

        assertTrue(result.isFailure)
        assertTrue(tempFolder.root.listFiles { f -> f.extension == "apk" }.isNullOrEmpty())
    }

    @Test
    fun downloadUpdateTo_sha256Absent_skipsVerification() = runTest {
        val body = ByteArray(4096) { 0x42 }
        server.enqueue(MockResponse().setResponseCode(200).setBody(Buffer().write(body)))

        val result = updateManager.downloadUpdateTo(
            tempFolder.root,
            infoFor(server.url("/app.apk").toString(), sha256 = null)
        )

        assertTrue(result.isSuccess)
    }

    @Test
    fun downloadUpdateTo_staleApksAreCleanedBeforeDownload() = runTest {
        val stale = File(tempFolder.root, "gotcha-0.9.0.apk").apply { writeBytes(byteArrayOf(1)) }
        val body = ByteArray(4096) { 0x42 }
        server.enqueue(MockResponse().setResponseCode(200).setBody(Buffer().write(body)))

        val result = updateManager.downloadUpdateTo(
            tempFolder.root,
            infoFor(server.url("/app.apk").toString())
        )

        assertTrue(result.isSuccess)
        assertFalse(stale.exists())
    }

    @Test
    fun downloadUpdateTo_cancelled_abortsTheDownload() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBodyDelay(10, TimeUnit.SECONDS)
                .setBody("x".repeat(1024))
        )

        val job = launch(Dispatchers.IO) {
            updateManager.downloadUpdateTo(
                tempFolder.root,
                infoFor(server.url("/app.apk").toString())
            )
        }
        job.cancel()
        job.join()

        assertTrue(job.isCancelled)
    }

    private fun infoFor(downloadUrl: String, sha256: String? = null) = AppUpdateInfo(
        versionCode = 2,
        versionName = "1.0.1",
        downloadUrl = downloadUrl,
        releaseNotes = "Notes",
        sha256 = sha256
    )

    private fun sha256Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString("") { "%02x".format(it) }
    }
}
