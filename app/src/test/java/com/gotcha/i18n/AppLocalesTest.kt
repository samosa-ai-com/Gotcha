package com.gotcha.i18n

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Properties

/**
 * The app's display languages are the `values-<lang>/` folders: the build turns
 * them into the LocaleConfig Android reads for per-app languages
 * (`generateLocaleConfig`). The interface may only be offered in a language
 * Gotcha can also speak and hear, so every such folder has to name a [Language].
 */
class AppLocalesTest {

    // Unit tests run with the module directory as the working directory.
    private val resDir = File("src/main/res")

    /** Language subtag of each `values-*` folder that carries a locale qualifier. */
    private fun translatedLanguages(): Map<String, String> =
        resDir.listFiles().orEmpty()
            .filter { it.isDirectory && it.name.startsWith("values-") }
            .mapNotNull { dir -> localeOf(dir.name)?.let { dir.name to it } }
            .toMap()

    /**
     * `values-hi`, `values-pt-rBR` and `values-b+zh+Hans` name a locale; `values-night`
     * and `values-v29` don't. Android's first qualifier is the locale when it is one.
     */
    private fun localeOf(folder: String): String? {
        val qualifiers = folder.removePrefix("values-")
        if (qualifiers.startsWith("b+")) return qualifiers.removePrefix("b+").substringBefore('+')
        val first = qualifiers.substringBefore('-')
        return first.takeIf { it.length in 2..3 && it.all(Char::isLowerCase) }
    }

    @Test
    fun `resources dir is found`() {
        assertTrue("expected ${resDir.absolutePath}", File(resDir, "values/strings.xml").isFile)
    }

    @Test
    fun `every translation folder is a voice language`() {
        val voiceLanguages = Language.entries.map { it.iso639 }.toSet()
        val strays = translatedLanguages().filterValues { it !in voiceLanguages }
        assertTrue(
            "Translations for languages Gotcha can't speak or hear: ${strays.keys}. " +
                "Add the language to Language first, or drop the folder.",
            strays.isEmpty()
        )
    }

    @Test
    fun `default strings are declared as English`() {
        // generateLocaleConfig needs this to list English alongside the translations.
        val props = Properties().apply {
            File(resDir, "resources.properties").inputStream().use { load(it) }
        }
        assertEquals("en-US", props.getProperty("unqualifiedResLocale"))
    }

    @Test
    fun `locale folder parsing ignores non-locale qualifiers`() {
        assertEquals("hi", localeOf("values-hi"))
        assertEquals("pt", localeOf("values-pt-rBR"))
        assertEquals("zh", localeOf("values-b+zh+Hans"))
        assertEquals("hi", localeOf("values-hi-night"))
        assertEquals(null, localeOf("values-night"))
        assertEquals(null, localeOf("values-v29"))
    }
}
