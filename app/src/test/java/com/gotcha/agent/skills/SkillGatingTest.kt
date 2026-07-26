package com.gotcha.agent.skills

import com.gotcha.connectors.ConnectorCatalog
import com.gotcha.testsupport.RepoPaths
import com.gotcha.tools.ToolRegistry
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The connector↔skill link: a skill is only eligible when at least one tool it
 * teaches is currently callable. Reads the bundled skill assets straight off
 * disk so the annotations on the shipped files are covered too.
 */
class SkillGatingTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun bundled(): List<Skill> =
        RepoPaths.file("app/src/main/assets/skills")
            .walkTopDown()
            .filter { it.isFile && it.extension == "json" }
            .map { json.decodeFromString(Skill.serializer(), it.readText()) }
            .toList()

    @Test
    fun `a skill with no requiresTools is always eligible`() {
        val skill = Skill(id = "x", instructions = "do a thing")
        assertTrue(skill.isAvailable(emptySet()))
        assertTrue(skill.isAvailable(ConnectorCatalog.allOwnedTools))
    }

    @Test
    fun `a skill is hidden only when every tool it teaches is hidden`() {
        val skill = Skill(
            id = "x",
            instructions = "use email",
            requiresTools = listOf("list_emails", "send_email")
        )
        assertTrue("one tool left is enough", skill.isAvailable(setOf("send_email")))
        assertFalse(skill.isAvailable(setOf("list_emails", "send_email")))
    }

    @Test
    fun `bundled skills parse and their requiresTools name real tools`() {
        val skills = bundled()
        assertTrue(skills.isNotEmpty())
        skills.forEach { skill ->
            skill.requiresTools.forEach { tool ->
                assertTrue(
                    "skill '${skill.id}' requires unknown tool '$tool'",
                    ToolRegistry.contains(tool)
                )
            }
        }
    }

    @Test
    fun `bundled skill ids are unique`() {
        val ids = bundled().map { it.id }
        assertEquals(ids.size, ids.toSet().size)
    }

    @Test
    fun `connector skills are split so the UI half survives the connector going away`() {
        val skills = bundled().associateBy { it.id }
        listOf("gmail", "notion", "outlook").forEach { family ->
            val connectorHalf = skills["${family}_connector"]
            val uiHalf = skills["${family}_ui"]
            assertTrue("$family connector skill missing", connectorHalf != null)
            assertTrue("$family UI skill missing", uiHalf != null)
            assertTrue(
                "$family connector skill must be gated",
                connectorHalf!!.requiresTools.isNotEmpty()
            )
            assertTrue(
                "$family UI skill must stay available with no connector",
                uiHalf!!.requiresTools.isEmpty()
            )
            // Both halves target the same apps, so the split is invisible to the user.
            assertEquals(connectorHalf.targetPackageNames, uiHalf.targetPackageNames)
        }
    }

    @Test
    fun `with nothing connected only the UI halves survive`() {
        val hidden = ConnectorCatalog.hiddenTools(emptySet())
        val eligible = bundled().filter { it.isAvailable(hidden) }.map { it.id }.toSet()

        assertTrue("gmail_ui" in eligible)
        assertTrue("notion_ui" in eligible)
        assertTrue("outlook_ui" in eligible)
        assertFalse("gmail_connector" in eligible)
        assertFalse("notion_connector" in eligible)
        assertFalse("outlook_connector" in eligible)
        // Device-backed skills are unaffected.
        assertTrue("calendar_operations" in eligible)
    }

    @Test
    fun `connecting a mail backend brings back the mail skills only`() {
        val hidden = ConnectorCatalog.hiddenTools(setOf("imap"))
        val eligible = bundled().filter { it.isAvailable(hidden) }.map { it.id }.toSet()

        assertTrue("gmail_connector" in eligible)
        assertTrue("outlook_connector" in eligible) // gated on list_emails OR list_tasks
        assertFalse("notion_connector" in eligible)
    }

    @Test
    fun `validator accepts requiresTools and rejects malformed entries`() {
        SkillImportValidator.validate(
            Skill(id = "abc", instructions = "hi", requiresTools = listOf("list_emails"))
        )
        val bad = Skill(id = "abc", instructions = "hi", requiresTools = listOf("Not A Tool"))
        val error = runCatching { SkillImportValidator.validate(bad) }.exceptionOrNull()
        assertTrue(error is SkillValidationException)
    }

    @Test
    fun `an unknown required tool fails closed`() {
        // Never matches anything, so the skill simply stays hidden rather than
        // becoming unconditionally visible.
        val skill = Skill(id = "x", instructions = "hi", requiresTools = listOf("no_such_tool"))
        assertTrue(skill.isAvailable(emptySet()))
        assertFalse(skill.isAvailable(setOf("no_such_tool")))
    }
}
