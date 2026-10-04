package com.gotcha.i18n

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Every translation takes the same arguments as its English string. A
 * translator who drops `%1$s`, or turns `%1$d` into `%1$s`, would otherwise
 * crash or garble that screen only in their language — where nobody testing in
 * English would see it.
 */
class TranslationFormatTest {

    // Unit tests run with the module directory as the working directory.
    private val resDir = File("src/main/res")

    private val entry = Regex("""<(string|item)( name="([^"]+)")?[^>]*>(.*?)</\1>""", RegexOption.DOT_MATCHES_ALL)
    private val placeholder = Regex("""%(\d+)\$([sd])""")

    /** name → placeholders, for strings and for each plural/array item (keyed name#index). */
    private fun placeholders(file: File): Map<String, Set<String>> {
        val result = mutableMapOf<String, Set<String>>()
        var parent = ""
        var index = 0
        file.readLines().forEach { line ->
            Regex("""<(?:plurals|string-array) name="([^"]+)"""").find(line)?.let {
                parent = it.groupValues[1]
                index = 0
            }
            entry.findAll(line).forEach { match ->
                val key = match.groupValues[3].ifEmpty { "$parent#${index++}" }
                result[key] = placeholder.findAll(match.groupValues[4]).map { it.value }.toSet()
            }
        }
        return result
    }

    @Test
    fun `translations take the same arguments as English`() {
        val english = placeholders(File(resDir, "values/strings.xml"))
        val translations = resDir.listFiles().orEmpty()
            .filter { it.isDirectory && it.name.startsWith("values-") }
            .map { File(it, "strings.xml") }
            .filter { it.isFile }
        // Plural forms differ by language (Hindi's "one" also covers zero, so it
        // has to show the count), so a form may use any argument English uses in
        // any form of that plural, and no other.
        val englishPlurals = english.entries
            .filter { '#' in it.key }
            .groupBy({ it.key.substringBefore('#') }, { it.value })
            .mapValues { (_, forms) -> forms.flatten().toSet() }
        val problems = translations.flatMap { file ->
            placeholders(file).mapNotNull { (key, args) ->
                val ok = if ('#' in key) {
                    englishPlurals[key.substringBefore('#')]?.containsAll(args) ?: true
                } else {
                    english[key]?.let { it == args } ?: true
                }
                val expected = english[key] ?: englishPlurals[key.substringBefore('#')]
                if (ok) null else "${file.parentFile.name}/$key: $args, English has $expected"
            }
        }
        assertTrue(problems.joinToString("\n"), problems.isEmpty())
    }

    @Test
    fun `there is at least one translation to check`() {
        assertTrue(File(resDir, "values-hi/strings.xml").isFile)
    }
}
