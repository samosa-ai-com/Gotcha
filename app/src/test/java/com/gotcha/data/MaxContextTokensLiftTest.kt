package com.gotcha.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Raising the default context budget reaches nobody on its own: an APK update
 * keeps the app's data directory, so every existing install reads back the 70k
 * it already stored. This lift is what actually moves them — silently, once,
 * writing its result back. Same reasoning as [SkinMigrationTest]: a mistake
 * here is one nobody reports.
 */
class MaxContextTokensLiftTest {

    @Test
    fun `an install still on the old default is raised and the new value stored`() {
        val lift = liftMaxContextTokens(stored = 70_000, alreadyLifted = false)
        assertEquals(DEFAULT_MAX_CONTEXT_TOKENS, lift.value)
        assertTrue("the raised value has to be persisted, not just returned", lift.writeBack)
    }

    /**
     * A fresh install has never written the key, so the read already returns the
     * current default. Nothing to lift, and nothing worth a write.
     */
    @Test
    fun `a fresh install is left alone`() {
        val lift = liftMaxContextTokens(
            stored = DEFAULT_MAX_CONTEXT_TOKENS,
            alreadyLifted = false
        )
        assertEquals(DEFAULT_MAX_CONTEXT_TOKENS, lift.value)
        assertFalse(lift.writeBack)
    }

    /**
     * The whole point of the one-shot flag. Someone who wants a small context
     * window gets to keep it — the lift must not chase them back up on the next
     * load.
     */
    @Test
    fun `70k chosen deliberately after the lift has run is kept`() {
        val lift = liftMaxContextTokens(stored = 70_000, alreadyLifted = true)
        assertEquals(70_000, lift.value)
        assertFalse(lift.writeBack)
    }

    /**
     * Only the exact old default is treated as "never chose". A tuned value is
     * a decision, and survives even on the run where the lift fires.
     */
    @Test
    fun `a hand-tuned budget is never rewritten`() {
        val lift = liftMaxContextTokens(stored = 32_000, alreadyLifted = false)
        assertEquals(32_000, lift.value)
        assertFalse(lift.writeBack)
    }

    /** A budget above the new default is a decision too, and must not be lowered. */
    @Test
    fun `a budget larger than the new default is left where it is`() {
        val lift = liftMaxContextTokens(stored = 400_000, alreadyLifted = false)
        assertEquals(400_000, lift.value)
        assertFalse(lift.writeBack)
    }
}
