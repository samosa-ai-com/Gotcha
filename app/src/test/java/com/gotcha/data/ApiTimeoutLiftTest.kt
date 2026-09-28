package com.gotcha.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 0 ("never time out") was the old default, so an update alone leaves every
 * existing install waiting forever on a model server that never replies (#104).
 * This lift is what moves them, once; same reasoning as [MaxContextTokensLiftTest].
 */
class ApiTimeoutLiftTest {

    @Test
    fun `an install still on the old default of 0 is lifted and the new value stored`() {
        val lift = liftApiTimeout(stored = 0L, alreadyLifted = false)
        assertEquals(DEFAULT_API_TIMEOUT_SECONDS, lift.value)
        assertTrue("the lifted value has to be persisted, not just returned", lift.writeBack)
    }

    /** A fresh install never wrote the key, so the read already returns the default. */
    @Test
    fun `a fresh install is left alone`() {
        val lift = liftApiTimeout(stored = DEFAULT_API_TIMEOUT_SECONDS, alreadyLifted = false)
        assertEquals(DEFAULT_API_TIMEOUT_SECONDS, lift.value)
        assertFalse(lift.writeBack)
    }

    /** The point of the one-shot flag: "no timeout" chosen after the lift is kept. */
    @Test
    fun `0 chosen after the lift has run is kept`() {
        val lift = liftApiTimeout(stored = 0L, alreadyLifted = true)
        assertEquals(0L, lift.value)
        assertFalse(lift.writeBack)
    }

    /** A typed-in timeout is a decision, and survives even on the run where the lift fires. */
    @Test
    fun `a hand-set timeout is never rewritten`() {
        listOf(30L, 600L).forEach { stored ->
            val lift = liftApiTimeout(stored = stored, alreadyLifted = false)
            assertEquals(stored, lift.value)
            assertFalse(lift.writeBack)
        }
    }

    @Test
    fun `a fresh Settings times out by default`() {
        assertEquals(DEFAULT_API_TIMEOUT_SECONDS, Settings().apiTimeoutSeconds)
    }
}
