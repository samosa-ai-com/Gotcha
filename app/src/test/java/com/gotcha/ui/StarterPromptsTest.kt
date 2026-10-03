package com.gotcha.ui

import androidx.compose.runtime.saveable.SaverScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [STARTER_PROMPTS] and each persona's starters are hand-edited lists that the
 * home screen draws from blindly. These are the invariants that drawing relies
 * on, checked for every pool the screen can draw from.
 */
class StarterPromptsTest {

    /** The default list plus every persona's own, by a name for failure messages. */
    private val pools: Map<String, List<StarterPrompt>> =
        mapOf("default" to STARTER_PROMPTS) +
            PERSONAS.filter { it.starters.isNotEmpty() }.associate { it.id to it.starters }

    @Test
    fun `offers enough starters to draw from`() {
        pools.forEach { (name, pool) ->
            assertTrue(
                "The home screen draws $STARTER_PROMPT_COUNT starters per session, " +
                    "so the $name list must hold at least that many",
                pool.size >= STARTER_PROMPT_COUNT
            )
        }
        assertTrue("Expected a spread of starters to draw from", STARTER_PROMPTS.size >= 5)
    }

    @Test
    fun `every starter has a label and a template`() {
        pools.forEach { (name, pool) ->
            pool.forEach { prompt ->
                assertTrue("Blank label in $name: $prompt", prompt.label.isNotBlank())
                assertTrue("Blank template in $name: $prompt", prompt.template.isNotBlank())
                // The chip has to sit three to a row on a phone.
                assertTrue(
                    "Label too long to fit a chip in $name: '${prompt.label}'",
                    prompt.label.length <= 20
                )
            }
        }
    }

    @Test
    fun `labels are unique within a pool`() {
        // The saver restores a drawn starter by its label, and the chips are
        // test-tagged by it, so duplicates would make both ambiguous. Only one
        // pool is on screen at a time, so pools may share a label.
        pools.forEach { (name, pool) ->
            val labels = pool.map { it.label }
            assertEquals("Duplicate label in $name", labels.size, labels.distinct().size)
        }
    }

    @Test
    fun `saver round-trips a drawn set from every pool`() {
        val scope = SaverScope { true }
        pools.forEach { (name, pool) ->
            val saver = starterPromptLabelsSaver(pool)
            val drawn = pool.take(STARTER_PROMPT_COUNT)
            val saved = requireNotNull(with(saver) { scope.save(drawn) })
            assertEquals(name, drawn, saver.restore(saved))
        }
    }

    @Test
    fun `saver refills a stale save from its own pool`() {
        val pool = requireNotNull(personaById("doctor")).starters
        val restored = requireNotNull(
            starterPromptLabelsSaver(pool).restore(listOf(pool.first().label, "A starter since removed"))
        )
        assertEquals(STARTER_PROMPT_COUNT, restored.size)
        assertEquals(pool.first(), restored.first())
        assertTrue(restored.all { it in pool })
    }

    @Test
    fun `no persona draws from the default list`() {
        assertSame(STARTER_PROMPTS, startersFor(null))
    }

    @Test
    fun `a persona with starters replaces the default list`() {
        val doctor = requireNotNull(personaById("doctor"))
        assertEquals(doctor.starters, startersFor(doctor))
        assertTrue(startersFor(doctor).none { it in STARTER_PROMPTS })
    }

    @Test
    fun `a persona without enough starters falls back to the default list`() {
        val bare = PERSONAS.first().copy(starters = emptyList())
        assertSame(STARTER_PROMPTS, startersFor(bare))
        val short = PERSONAS.first().let { it.copy(starters = it.starters.take(STARTER_PROMPT_COUNT - 1)) }
        assertSame(STARTER_PROMPTS, startersFor(short))
    }
}
