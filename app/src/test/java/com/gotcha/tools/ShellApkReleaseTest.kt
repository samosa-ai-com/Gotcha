package com.gotcha.tools

import com.gotcha.auth.ShellApkRelease
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The APK answer's shape rules.
 *
 * These exist because a *plausible* answer that is subtly wrong is worse than no answer: it
 * survives as far as the installer and fails there, where the message is confusing. Everything
 * needed to reject it is in the response, so it is rejected here.
 */
class ShellApkReleaseTest {

    private fun release(
        version: String = "0.2.0",
        sha: String = "a".repeat(64),
        size: Long = 1234,
        path: String = "/gotcha/shell/download/${"T".repeat(43)}",
        expires: Long = 3600
    ) = ShellApkRelease(version, sha, size, path, expires)

    @Test
    fun `a complete answer is usable`() {
        assertTrue(release().isUsable())
    }

    @Test
    fun `blank version is rejected`() {
        assertFalse(release(version = "").isUsable())
    }

    @Test
    fun `zero size is rejected`() {
        assertFalse(release(size = 0).isUsable())
    }

    @Test
    fun `negative size is rejected`() {
        assertFalse(release(size = -1).isUsable())
    }

    @Test
    fun `short digest is rejected`() {
        assertFalse(release(sha = "a".repeat(63)).isUsable())
    }

    @Test
    fun `uppercase digest is rejected`() {
        // The companion and the hub both speak lowercase hex; accepting uppercase here would
        // compare unequal against a lowercased digest later.
        assertFalse(release(sha = "A".repeat(64)).isUsable())
    }

    @Test
    fun `non hex digest is rejected`() {
        assertFalse(release(sha = "z".repeat(64)).isUsable())
    }

    @Test
    fun `absolute url is rejected`() {
        // The path is relative by contract; an absolute one means something is trying to send
        // the download somewhere the configured origin does not cover.
        assertFalse(release(path = "https://evil.example/apk").isUsable())
    }

    @Test
    fun `other paths are rejected`() {
        assertFalse(release(path = "/v1/gotcha/shell/download/x").isUsable())
        assertFalse(release(path = "/gotcha/shell/release").isUsable())
        assertFalse(release(path = "").isUsable())
    }

    @Test
    fun `path traversal is rejected`() {
        // The path is concatenated onto the base URL and fetched with the session token, so a
        // prefix-only check would let a hostile answer steer an authenticated request to
        // another endpoint on the same host.
        assertFalse(release(path = "/gotcha/shell/download/../../admin/keys").isUsable())
        assertFalse(release(path = "/gotcha/shell/download/${"T".repeat(43)}/../../../v1/me").isUsable())
    }

    @Test
    fun `query strings are rejected`() {
        assertFalse(release(path = "/gotcha/shell/download/${"T".repeat(43)}?redirect=https://evil.example").isUsable())
        assertFalse(release(path = "/gotcha/shell/download/${"T".repeat(43)}#frag").isUsable())
    }

    @Test
    fun `wrong token length is rejected`() {
        assertFalse(release(path = "/gotcha/shell/download/short").isUsable())
        assertFalse(release(path = "/gotcha/shell/download/${"T".repeat(44)}").isUsable())
    }

    @Test
    fun `protocol relative and absolute forms are rejected`() {
        assertFalse(release(path = "//evil.example/gotcha/shell/download/${"T".repeat(43)}").isUsable())
        assertFalse(release(path = "https://evil.example/gotcha/shell/download/${"T".repeat(43)}").isUsable())
    }

    @Test
    fun `default instance is not usable`() {
        assertFalse(ShellApkRelease().isUsable())
    }

    @Test
    fun `expiry is passed through`() {
        assertEquals(3600L, release().expiresInSeconds)
    }
}
