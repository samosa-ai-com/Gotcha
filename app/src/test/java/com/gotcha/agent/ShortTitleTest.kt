package com.gotcha.agent

import org.junit.Assert.assertEquals
import org.junit.Test

/** The placeholder chat name in the ongoing notification (issue #105). */
class ShortTitleTest {

    @Test
    fun `a short message is kept whole`() {
        assertEquals("Plan my trip", shortTitle("  Plan my trip "))
    }

    @Test
    fun `a long message is cut at a word`() {
        assertEquals(
            "Find the 10 largest files in my…",
            shortTitle("Find the 10 largest files in my Downloads folder and describe each one")
        )
    }

    @Test
    fun `line breaks become spaces`() {
        assertEquals("First line second line", shortTitle("First line\nsecond line"))
    }

    @Test
    fun `one long word is cut where it has to be`() {
        assertEquals("a".repeat(40) + "…", shortTitle("a".repeat(60)))
    }
}
