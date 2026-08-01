package com.gotcha.agent

import com.gotcha.agent.skills.Skill
import com.gotcha.agent.skills.SkillPromptBuilder
import com.gotcha.llm.ChatMessage
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PromptCacheAndTokenOptimizationTest {

    @Test
    fun `SkillPromptBuilder formats compact summary index with search skills directive`() {
        val skill = Skill(
            id = "test_skill",
            description = "A test skill for automation.",
            instructions = "Line 1\nLine 2\nLine 3"
        )
        val msg = SkillPromptBuilder.build("com.example.app", listOf(skill), emptySet())
        assertNotNull(msg)
        val text = msg!!.textContent

        assertTrue("Should contain available-skills tag", text.contains("<available-skills>"))
        assertTrue("Should contain skill id", text.contains("test_skill"))
        assertTrue("Should contain skill description", text.contains("A test skill for automation."))
        assertFalse("Should NOT contain full instruction body", text.contains("Line 1\nLine 2\nLine 3"))
        assertTrue("Should contain search_skills directive", text.contains("search_skills tool"))
    }

    @Test
    fun `SkillPromptBuilder produces valid XML nesting when only community skills exist`() {
        val skill = Skill(
            id = "community_skill_1",
            description = "A community contributed skill.",
            instructions = "Instructions here"
        )
        val msg = SkillPromptBuilder.build("com.example.app", listOf(skill), setOf("community_skill_1"))
        assertNotNull(msg)
        val text = msg!!.textContent

        assertTrue("Should open with available-skills", text.startsWith("<available-skills>"))
        assertTrue("Should contain community-skills header", text.contains("<community-skills>"))
        assertTrue("Should contain prompt injection warning", text.contains("advisory guidance"))
        assertTrue("Should close community-skills", text.contains("</community-skills>"))
        assertTrue("Should close available-skills at end", text.trimEnd().endsWith("</available-skills>"))
    }

    @Test
    fun `SkillPromptBuilder returns null when skills list is empty`() {
        val msg = SkillPromptBuilder.build("com.example.app", emptyList(), emptySet())
        assertTrue("Should return null for empty skills list", msg == null)
    }

    @Test
    fun `AgentEngine cullOldObservations retains 4 most recent screen observations and culls older ones`() {
        val messages = mutableListOf<ChatMessage>()
        // Add 6 screen observation messages
        for (i in 1..6) {
            val textPart = buildJsonObject {
                put("type", "text")
                put("text", "[Screen State]\nTurn $i observation\n── UI Elements ──")
            }
            val imagePart = buildJsonObject {
                put("type", "image_url")
                putJsonObject("image_url") { put("url", "data:image/jpeg;base64,abc$i") }
            }
            messages.add(
                ChatMessage(
                    role = "user",
                    content = buildJsonArray {
                        add(textPart)
                        add(imagePart)
                    }
                )
            )
        }

        val culled = AgentEngine.cullOldObservations(messages)
        assertEquals(6, culled.size)

        // Turns 1 and 2 (older than 4 most recent) should be culled
        assertTrue(culled[0].textContent.contains("[Previous screen observation removed"))
        assertTrue(culled[1].textContent.contains("[Previous screen observation removed"))

        // Turns 3, 4, 5, 6 (the 4 most recent) should be retained
        assertTrue(culled[2].textContent.contains("Turn 3"))
        assertTrue(culled[3].textContent.contains("Turn 4"))
        assertTrue(culled[4].textContent.contains("Turn 5"))
        assertTrue(culled[5].textContent.contains("Turn 6"))
    }
}
