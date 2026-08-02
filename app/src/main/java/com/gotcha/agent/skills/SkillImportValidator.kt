package com.gotcha.agent.skills

/**
 * Strict validation rules for imported skills. Rejects anything that could be
 * used to escape the skills directory, inflate the prompt, or inject control
 * characters into the system prompt.
 */
@Suppress("ThrowsCount")
object SkillImportValidator {

    private val ID_REGEX = Regex("^[a-z][a-z0-9_]{2,63}$")
    private val PACKAGE_REGEX = Regex("^[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z0-9_]+)+$")
    private val TOOL_NAME_REGEX = Regex("^[a-z][a-z0-9_]{1,63}$")

    const val MAX_ID_LENGTH = 64
    const val MAX_TITLE_LENGTH = 120
    const val MAX_DESCRIPTION_LENGTH = 1024
    const val MAX_INSTRUCTIONS_LENGTH = 32 * 1024
    const val MAX_TARGET_PACKAGES = 32
    const val MAX_REQUIRED_TOOLS = 32
    const val MAX_TOTAL_INSTRUCTIONS = 64 * 1024

    fun validate(skill: Skill) {
        if (skill.id.isBlank()) throw SkillValidationException("id is empty")
        if (skill.id.length > MAX_ID_LENGTH) {
            throw SkillValidationException("id exceeds $MAX_ID_LENGTH characters")
        }
        if (!ID_REGEX.matches(skill.id)) {
            throw SkillValidationException(
                "id must match $ID_REGEX (lowercase, starts with a letter, 3-64 chars)"
            )
        }
        if (skill.instructions.isBlank()) {
            throw SkillValidationException("instructions is empty")
        }
        if (skill.instructions.length > MAX_INSTRUCTIONS_LENGTH) {
            throw SkillValidationException(
                "instructions exceed $MAX_INSTRUCTIONS_LENGTH characters"
            )
        }
        if (skill.description.length > MAX_DESCRIPTION_LENGTH) {
            throw SkillValidationException("description exceeds $MAX_DESCRIPTION_LENGTH characters")
        }
        if (skill.title.length > MAX_TITLE_LENGTH) {
            throw SkillValidationException("title exceeds $MAX_TITLE_LENGTH characters")
        }
        if (skill.targetPackageNames.size > MAX_TARGET_PACKAGES) {
            throw SkillValidationException("targetPackageNames exceeds $MAX_TARGET_PACKAGES entries")
        }
        skill.targetPackageNames.forEach { pkg ->
            if (pkg != "*" && !PACKAGE_REGEX.matches(pkg)) {
                throw SkillValidationException("invalid target package name: $pkg")
            }
        }
        if (skill.requiresTools.size > MAX_REQUIRED_TOOLS) {
            throw SkillValidationException("requiresTools exceeds $MAX_REQUIRED_TOOLS entries")
        }
        // Names only — an unknown name simply never matches, which fails closed
        // (the skill stays hidden) rather than exposing anything.
        skill.requiresTools.forEach { tool ->
            if (!TOOL_NAME_REGEX.matches(tool)) {
                throw SkillValidationException("invalid tool name in requiresTools: $tool")
            }
        }
        if (containsControlChars(skill.instructions)) {
            throw SkillValidationException("instructions contains control characters")
        }
        if (containsControlChars(skill.description)) {
            throw SkillValidationException("description contains control characters")
        }
    }

    /** Strip control characters and zero-width characters from text fields. */
    fun sanitize(text: String): String =
        text.filter { c -> !isControlChar(c) }

    private fun containsControlChars(text: String): Boolean =
        text.any { c -> isControlChar(c) }

    /**
     * Returns true for ASCII control characters (incl. CR/LF/TAB), other Cc/Cf
     * code points, and zero-width / BOM characters. Skips plain whitespace.
     */
    private fun isControlChar(c: Char): Boolean {
        val cp = c.code
        if (cp == 0x09 || cp == 0x0A || cp == 0x0D) return false
        if (cp < 0x20) return true
        if (cp in 0x7F..0x9F) return true
        if (cp == 0x200B || cp == 0x200C || cp == 0x200D || cp == 0xFEFF) return true
        return false
    }
}

class SkillValidationException(message: String) : IllegalArgumentException(message)
