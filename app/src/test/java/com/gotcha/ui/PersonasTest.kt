package com.gotcha.ui

import com.gotcha.tools.AgentMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [PERSONAS] is a hand-edited list the home screen draws blindly and chat files
 * refer to by id. These are the invariants both rely on — plus the one that is
 * about the product rather than the plumbing: a persona that dresses up as a
 * regulated profession has to say it isn't one.
 */
class PersonasTest {

    @Test
    fun `offers a spread of personas`() {
        assertTrue("Expected a curated set to choose from", PERSONAS.size >= 4)
    }

    @Test
    fun `every persona has an id, a label and a prompt`() {
        PERSONAS.forEach { persona ->
            assertTrue("Blank id in $persona", persona.id.isNotBlank())
            assertTrue("Blank label in $persona", persona.label.isNotBlank())
            // The chips wrap, but a long one still looks broken on a phone.
            assertTrue("Label too long to fit a chip: '${persona.label}'", persona.label.length <= 20)
            assertTrue(
                "Persona '${persona.id}' needs a prompt with something in it",
                persona.systemPrompt.length >= 120
            )
        }
    }

    @Test
    fun `ids are unique and stable-looking`() {
        // Ids are persisted on chat files and used as test tags, so duplicates
        // would make a reopened chat ambiguous.
        val ids = PERSONAS.map { it.id }
        assertEquals(ids.size, ids.distinct().size)
        ids.forEach { id ->
            assertTrue("Persona id '$id' should be lowercase snake_case", id.matches(Regex("[a-z0-9_]+")))
        }
    }

    @Test
    fun `every seeded persona starts read-only`() {
        // A persona is something to talk to; none of the seeded set has any
        // reason to start with the device unlocked.
        PERSONAS.forEach { persona ->
            assertEquals(persona.id, AgentMode.MONITOR, persona.defaultAgent)
        }
    }

    @Test
    fun `professional personas carry their disclaimer`() {
        val doctor = requireNotNull(personaById("doctor"))
        assertTrue(
            "The Doctor persona must refuse to pass as the user's own doctor",
            doctor.systemPrompt.contains("not making a diagnosis")
        )
        assertTrue(
            "The Doctor persona must route emergencies to real care",
            doctor.systemPrompt.contains("emergency")
        )
        val coach = requireNotNull(personaById("fitness_coach"))
        assertTrue(
            "The Fitness Coach persona must not stand in for a clinician",
            coach.systemPrompt.contains("not a doctor")
        )
    }

    @Test
    fun `lookup resolves known ids and tolerates the rest`() {
        assertNotNull(personaById(PERSONAS.first().id))
        // A persona dropped in an app update leaves its id behind on old chats.
        assertNull(personaById("persona-that-was-removed"))
        assertNull(personaById(null))
    }
}
