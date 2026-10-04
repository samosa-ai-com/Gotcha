package com.gotcha.agent

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.gotcha.R
import com.gotcha.i18n.stringLookup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.net.SocketTimeoutException
import java.net.UnknownHostException

@RunWith(RobolectricTestRunner::class)
class FriendlyModelErrorTest {

    private val strings = ApplicationProvider.getApplicationContext<Context>().stringLookup()

    /** A model server that never answers ends the run with this, not a network hint (#104). */
    @Test
    fun `a timeout says the model did not respond in time`() {
        assertEquals(
            strings(R.string.error_model_timeout),
            friendlyModelError(SocketTimeoutException("timeout"), strings = strings)
        )
    }

    @Test
    fun `other failures keep the generic wording`() {
        val message = friendlyModelError(UnknownHostException("api.openai.com"), strings = strings)
        assertTrue(message, message.contains("Cannot connect to server"))
    }
}
