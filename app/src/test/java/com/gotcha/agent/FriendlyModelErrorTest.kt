package com.gotcha.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.SocketTimeoutException
import java.net.UnknownHostException

class FriendlyModelErrorTest {

    /** A model server that never answers ends the run with this, not a network hint (#104). */
    @Test
    fun `a timeout says the model did not respond in time`() {
        assertEquals(MODEL_TIMEOUT_MESSAGE, friendlyModelError(SocketTimeoutException("timeout")))
    }

    @Test
    fun `other failures keep the generic wording`() {
        val message = friendlyModelError(UnknownHostException("api.openai.com"))
        assertTrue(message, message.contains("Cannot connect to server"))
    }
}
