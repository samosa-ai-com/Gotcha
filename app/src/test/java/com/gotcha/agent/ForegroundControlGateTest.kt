package com.gotcha.agent

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ForegroundControlGateTest {

    private val noArgs = JsonObject(emptyMap())

    /** An ask that counts how often it was raised and answers with [answer]. */
    private class CountingAsk(private val answer: suspend () -> Boolean) {
        var asks = 0
        suspend operator fun invoke(): Boolean {
            asks++
            return answer()
        }
    }

    @Test
    fun `asks once and keeps an allow for the rest of the request`() = runTest {
        val ask = CountingAsk { true }
        var starts = 0
        val gate = ForegroundControlGate(ask = { _, _ -> ask() }, onControlStarted = { starts++ })

        assertNull(gate.check("open_app", noArgs))
        assertNull(gate.check("tap", noArgs))
        assertNull(gate.check("read_screen", noArgs))

        assertEquals(1, ask.asks)
        assertEquals(1, starts)
        assertTrue(gate.inControl)
    }

    @Test
    fun `keeps a denial for the rest of the request`() = runTest {
        val ask = CountingAsk { false }
        val gate = ForegroundControlGate(ask = { _, _ -> ask() })

        val first = gate.check("open_app", noArgs)
        val second = gate.check("swipe", noArgs)

        assertEquals(1, ask.asks)
        assertFalse(first!!.success)
        assertEquals(ForegroundControlGate.DENIED_MESSAGE, second!!.message)
        assertFalse(gate.inControl)
    }

    @Test
    fun `reset forgets the answer and reports whether control was taken`() = runTest {
        val ask = CountingAsk { true }
        val gate = ForegroundControlGate(ask = { _, _ -> ask() })
        gate.check("open_app", noArgs)

        assertTrue(gate.reset())
        assertFalse("a second reset has nothing to end", gate.reset())

        gate.check("open_app", noArgs)
        assertEquals(2, ask.asks)
    }

    @Test
    fun `background and info tools pass without asking`() = runTest {
        val ask = CountingAsk { false }
        val gate = ForegroundControlGate(ask = { _, _ -> ask() })

        assertNull(gate.check("write_file", noArgs))
        assertNull(gate.check("web_search", noArgs))
        assertNull(gate.check("dial_number", noArgs))
        assertNull(gate.check("task", noArgs))

        assertEquals(0, ask.asks)
        assertFalse(gate.reset())
    }

    @Test
    fun `calls racing the first ask share its answer`() = runTest {
        val answer = CompletableDeferred<Boolean>()
        val ask = CountingAsk { answer.await() }
        val gate = ForegroundControlGate(ask = { _, _ -> ask() })

        val a = async { gate.check("open_app", noArgs) }
        val b = async { gate.check("tap", noArgs) }
        yield()
        answer.complete(true)

        assertNull(a.await())
        assertNull(b.await())
        assertEquals(1, ask.asks)
    }

    @Test
    fun `the prompt names the app, the reason and what a denial means`() {
        val text = ForegroundControlRequest(
            toolName = "open_app",
            appLabel = "WhatsApp",
            userRequest = "text Sam I'm late"
        ).promptText()

        assertTrue(text.contains("WhatsApp"))
        assertTrue(text.contains("text Sam I'm late"))
        assertTrue(text.contains("bring WhatsApp to the front"))
        assertTrue(text.contains("If you deny"))
    }
}
