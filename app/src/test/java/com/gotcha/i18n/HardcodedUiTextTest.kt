package com.gotcha.i18n

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Keeps the screens translatable: display text in `ui/` comes from string
 * resources, so a `Text("Save")` or `contentDescription = "Close"` written
 * straight into a composable would show in English whatever the app language.
 *
 * Deliberately narrow — it catches the shapes that are always display text and
 * leaves the rest (test tags, animation labels, URLs) alone. Text the model
 * reads stays English and lives outside `ui/`.
 */
class HardcodedUiTextTest {

    // Unit tests run with the module directory as the working directory.
    private val uiDir = File("src/main/java/com/gotcha/ui")

    private val displayText = listOf(
        Regex("""\bText\(\s*"(?!https?://)[^"]*\p{L}{2}"""),
        Regex("""\b(?:contentDescription|placeholderText) = "[^"]*\p{L}{2}""")
    )

    @Test
    fun `ui sources are found`() {
        assertTrue("expected ${uiDir.absolutePath}", uiDir.isDirectory)
    }

    @Test
    fun `screens take their text from string resources`() {
        val offenders = uiDir.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .flatMap { file ->
                file.readLines().mapIndexedNotNull { index, line ->
                    val comment = line.trimStart().let { it.startsWith("//") || it.startsWith("*") }
                    if (!comment && displayText.any { it.containsMatchIn(line) }) {
                        "${file.relativeTo(uiDir)}:${index + 1}: ${line.trim()}"
                    } else {
                        null
                    }
                }
            }
            .toList()
        assertTrue(
            "Hardcoded display text; move it to res/values/strings.xml:\n" + offenders.joinToString("\n"),
            offenders.isEmpty()
        )
    }
}
