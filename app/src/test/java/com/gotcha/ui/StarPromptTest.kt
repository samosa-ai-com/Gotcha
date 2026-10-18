package com.gotcha.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** When the "Enjoying Gotcha?" ask is due. */
class StarPromptTest {

    private val day = 24L * 60L * 60L * 1000L
    private val installedAt = 1_000_000_000_000L

    private fun due(
        daysSinceInstall: Double,
        daysSinceShown: Double? = null,
        dismissals: Int = 0,
        answered: Boolean = false
    ): Boolean {
        val now = installedAt + (daysSinceInstall * day).toLong()
        val lastShownAt = daysSinceShown?.let { now - (it * day).toLong() } ?: 0L
        return isStarPromptDue(now, installedAt, lastShownAt, dismissals, answered)
    }

    @Test
    fun `waits three days after install`() {
        assertFalse(due(daysSinceInstall = 0.0))
        assertFalse(due(daysSinceInstall = 2.9))
        assertTrue(due(daysSinceInstall = 3.0))
    }

    @Test
    fun `waits five days between asks`() {
        assertFalse(due(daysSinceInstall = 10.0, daysSinceShown = 0.0))
        assertFalse(due(daysSinceInstall = 10.0, daysSinceShown = 4.9))
        assertTrue(due(daysSinceInstall = 10.0, daysSinceShown = 5.0))
    }

    @Test
    fun `an answer ends it for good`() {
        assertFalse(due(daysSinceInstall = 100.0, daysSinceShown = 50.0, answered = true))
    }

    @Test
    fun `four dismissals end it for good`() {
        assertTrue(due(daysSinceInstall = 100.0, daysSinceShown = 50.0, dismissals = 3))
        assertFalse(due(daysSinceInstall = 100.0, daysSinceShown = 50.0, dismissals = 4))
    }
}
