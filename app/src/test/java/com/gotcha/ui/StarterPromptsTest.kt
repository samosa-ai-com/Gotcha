package com.gotcha.ui

import androidx.compose.runtime.saveable.SaverScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [STARTER_PROMPTS] is a hand-edited list that the home screen draws from
 * blindly. These are the invariants that drawing relies on.
 */
class StarterPromptsTest {

    @Test
    fun `offers enough starters to draw from`() {
        assertTrue(
            "The home screen draws $STARTER_PROMPT_COUNT starters per session, " +
                "so the list must hold at least that many",
            STARTER_PROMPTS.size >= STARTER_PROMPT_COUNT
        )
        assertTrue("Expected a spread of starters to draw from", STARTER_PROMPTS.size >= 5)
    }

    @Test
    fun `every starter has a label and a template`() {
        STARTER_PROMPTS.forEach { prompt ->
            assertTrue("Blank label in $prompt", prompt.label.isNotBlank())
            assertTrue("Blank template in $prompt", prompt.template.isNotBlank())
            // The chip has to sit three to a row on a phone.
            assertTrue(
                "Label too long to fit a chip: '${prompt.label}'",
                prompt.label.length <= 20
            )
        }
    }

    @Test
    fun `labels are unique`() {
        // The saver restores a drawn starter by its label, and the chips are
        // test-tagged by it, so duplicates would make both ambiguous.
        val labels = STARTER_PROMPTS.map { it.label }
        assertEquals(labels.size, labels.distinct().size)
    }

    @Test
    fun `saver round-trips a drawn set`() {
        val drawn = STARTER_PROMPTS.take(STARTER_PROMPT_COUNT)
        val scope = SaverScope { true }
        val saved = requireNotNull(with(StarterPromptLabelsSaver) { scope.save(drawn) })
        assertEquals(drawn, StarterPromptLabelsSaver.restore(saved))
    }
}
