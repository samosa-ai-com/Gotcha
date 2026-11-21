package com.gotcha.tools

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The runtime source must never leave the phone without the account session.
 * A signed-out phone has no token, so the fetch returns null without touching
 * the network — and the caller reports the runtime as missing instead.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CompanionRuntimeSourceTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `no token means no request`() = runTest {
        CompanionRuntimeSource.resetForTest()
        assertNull(CompanionRuntimeSource.fetch(context))
    }
}
