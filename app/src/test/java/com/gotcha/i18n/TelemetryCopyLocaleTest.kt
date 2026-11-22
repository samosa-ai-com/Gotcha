package com.gotcha.i18n

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The telemetry copy added by this change has to exist in every shipped
 * language, and the claims it replaces (off by default, ninety days, delete
 * my data) must be gone from all of them. A stale translation of a removed
 * claim is worse than a missing one, so both directions are checked here.
 */
class TelemetryCopyLocaleTest {

    // Unit tests run with the module directory as the working directory.
    private val resDir = File("src/main/res")

    private val locales = listOf("ar", "de", "es", "fr", "hi", "it", "ja", "pt-rBR", "zh-rCN")

    private val required = setOf(
        "legal_consent_usage_data_label",
        "legal_consent_usage_data_hint",
        "privacy_usage_data_intro",
        "privacy_usage_data_status_not_configured",
        "telemetry_event_consent",
        "telemetry_event_session_profile"
    )

    private val removed = setOf(
        "privacy_enable_dialog_title",
        "privacy_enable_dialog_body",
        "privacy_enable_dialog_confirm",
        "privacy_enable_dialog_dismiss",
        "privacy_delete_usage_data",
        "privacy_delete_dialog_title",
        "privacy_delete_dialog_body",
        "privacy_delete_dialog_confirm",
        "privacy_delete_done",
        "privacy_delete_failed"
    )

    private val files: List<File> =
        listOf(File(resDir, "values/strings.xml")) +
            locales.map { File(resDir, "values-$it/strings.xml") }

    private fun keys(file: File): Set<String> {
        val entry = Regex("""<(?:string|string-array|plurals)\s+name="([^"]+)"""")
        return entry.findAll(file.readText()).map { it.groupValues[1] }.toSet()
    }

    @Test
    fun `every locale carries the new telemetry strings`() {
        val problems = files.flatMap { file ->
            val present = keys(file)
            val parent = file.parentFile.name
            required.filter { it !in present }.map { "$parent is missing $it" }
        }
        assertTrue(problems.joinToString("\n"), problems.isEmpty())
    }

    @Test
    fun `the new keys are one set across the ten files`() {
        // Only the keys this change owns: the translations already lag on
        // other strings, which is out of scope here.
        val keySets = files.associate { it.parentFile.name to keys(it).filter { k -> k in required }.toSet() }
        val reference = keySets.getValue("values")
        val problems = keySets.mapNotNull { (locale, present) ->
            (reference - present).takeIf { it.isNotEmpty() }?.let { "$locale is missing $it" }
        }
        assertTrue(problems.joinToString("\n"), problems.isEmpty())
    }

    @Test
    fun `no locale keeps the delete flow or the old copy`() {
        val staleClaims = listOf(
            "Delete my usage data",
            "off unless you turn it on",
            "Aus, solange du es nicht einschaltest",
            "Kept for 90 days",
            "Aufbewahrung 90 Tage",
            "90 Tage"
        )
        val problems = files.flatMap { file ->
            val present = keys(file)
            val parent = file.parentFile.name
            val kept = removed.filter { it in present }.map { "$parent still defines $it" }
            val text = file.readText()
            val claims = staleClaims.filter { it in text }.map { "$parent still says '$it'" }
            kept + claims
        }
        assertTrue(problems.joinToString("\n"), problems.isEmpty())
    }
}
