package com.gotcha.tools

import com.gotcha.auth.ShellApkRelease
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The per-account failure cooldown.
 *
 * The route allows six requests an hour, so a retry loop must not spend them. The rule that
 * matters is the *binding*: one account's cooldown must never suppress another's first attempt,
 * which is exactly the bug the runtime source was fixed for.
 */
class CompanionApkSourceTest {

    @After
    fun tearDown() {
        CompanionApkSource.resetForTest()
    }

    @Test
    fun `a failure cools down only the account that failed`() {
        CompanionApkSource.noteFailure("token-a".hashCode())

        assertTrue(CompanionApkSource.isCoolingDownFor("token-a".hashCode()))
        assertFalse(CompanionApkSource.isCoolingDownFor("token-b".hashCode()))
    }

    @Test
    fun `clearing state lets the next attempt through`() {
        CompanionApkSource.noteFailure("token-a".hashCode())
        CompanionApkSource.resetForTest()
        assertFalse(CompanionApkSource.isCoolingDownFor("token-a".hashCode()))
    }

    @Test
    fun `a rejected release is recorded against its own account`() {
        val release = ShellApkRelease(
            version = "0.2.0",
            sha256 = "a".repeat(64),
            sizeBytes = 10,
            downloadPath = "/gotcha/shell/download/${"T".repeat(43)}",
            expiresInSeconds = 3600
        )
        val resolved = CompanionApkSource.ResolvedRelease(release, "token-a".hashCode())
        resolved.noteRejected()

        assertTrue(CompanionApkSource.isCoolingDownFor("token-a".hashCode()))
        assertFalse(CompanionApkSource.isCoolingDownFor("token-b".hashCode()))
    }

    @Test
    fun `no failure means no cooldown`() {
        assertFalse(CompanionApkSource.isCoolingDownFor("anything".hashCode()))
    }
}
