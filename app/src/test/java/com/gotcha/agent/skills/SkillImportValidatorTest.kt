package com.gotcha.agent.skills

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class SkillImportValidatorTest {

    private val base = Skill(
        id = "good_id",
        instructions = "Do X then Y.",
        description = "A useful skill",
        title = "Good Skill"
    )

    @Test
    fun `accepts a well-formed skill`() {
        SkillImportValidator.validate(base)
    }

    @Test
    fun `rejects empty id`() {
        assertFailsWithMessage("id is empty") {
            SkillImportValidator.validate(base.copy(id = ""))
        }
    }

    @Test
    fun `rejects id with uppercase`() {
        assertFailsWithMessage("id must match") {
            SkillImportValidator.validate(base.copy(id = "BadId"))
        }
    }

    @Test
    fun `rejects id starting with digit`() {
        assertFailsWithMessage("id must match") {
            SkillImportValidator.validate(base.copy(id = "1bad"))
        }
    }

    @Test
    fun `rejects id that is too short`() {
        assertFailsWithMessage("id must match") {
            SkillImportValidator.validate(base.copy(id = "ab"))
        }
    }

    @Test
    fun `rejects blank instructions`() {
        assertFailsWithMessage("instructions is empty") {
            SkillImportValidator.validate(base.copy(instructions = "   "))
        }
    }

    @Test
    fun `rejects oversized instructions`() {
        val giant = "x".repeat(SkillImportValidator.MAX_INSTRUCTIONS_LENGTH + 1)
        assertFailsWithMessage("exceed") {
            SkillImportValidator.validate(base.copy(instructions = giant))
        }
    }

    @Test
    fun `rejects malformed target package name`() {
        assertFailsWithMessage("invalid target package") {
            SkillImportValidator.validate(base.copy(targetPackageNames = listOf("not.a.valid..pkg")))
        }
    }

    @Test
    fun `accepts wildcard target package`() {
        SkillImportValidator.validate(base.copy(targetPackageNames = listOf("*")))
    }

    @Test
    fun `rejects ESCOBEL control chars in instructions`() {
        // Vertical tab is a control char.
        val bad = "foo\u000Bbar"
        assertFailsWithMessage("control characters") {
            SkillImportValidator.validate(base.copy(instructions = bad))
        }
    }

    @Test
    fun `sanitize preserves conventional whitespace`() {
        val ok = SkillImportValidator.sanitize("line1\nline2\tcolumn")
        assertEquals("line1\nline2\tcolumn", ok)
    }

    @Test
    fun `sanitize strips zero-width chars`() {
        val cleaned = SkillImportValidator.sanitize("a\u200Bb\u200Cc")
        assertEquals("abc", cleaned)
    }

    private fun assertFailsWithMessage(needle: String, block: () -> Unit) {
        try {
            block()
            fail("Expected SkillValidationException containing '$needle'")
        } catch (e: SkillValidationException) {
            assertTrue(
                "Expected message to contain '$needle' but was '${e.message}'",
                e.message?.contains(needle, ignoreCase = true) == true
            )
        }
    }
}
