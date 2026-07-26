package com.gotcha.tools

import com.gotcha.testsupport.RepoPaths
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The feature-coverage gate for issue #9.
 *
 * Every tool in [ToolDefinitions.all] must be classified in
 * `app/src/test/resources/feature-test-coverage.json`, and the human-readable
 * `docs/FEATURE_TEST_COVERAGE.md` is *generated from* that manifest rather than
 * hand-written, so the published page cannot drift from reality.
 *
 * Adding a tool without classifying it fails this test. That is the point.
 *
 * To regenerate the doc after editing the manifest:
 * ```
 * ./gradlew :app:testDebugUnitTest -PupdateCoverageDocs=true
 * ```
 */
class FeatureCoverageManifestTest {

    @Serializable
    data class ToolCoverage(
        val tool: String,
        val tier: String,
        val tests: List<String> = emptyList(),
        val notes: String? = null,
        val reason: String? = null,
        val manualSteps: String? = null
    )

    @Serializable
    data class CoverageManifest(val tools: List<ToolCoverage>)

    private val manifest: CoverageManifest by lazy {
        val stream = javaClass.classLoader!!.getResourceAsStream(MANIFEST_RESOURCE)
            ?: error("$MANIFEST_RESOURCE missing from the unit-test resources")
        Json { ignoreUnknownKeys = false }.decodeFromString(
            CoverageManifest.serializer(),
            stream.bufferedReader().use { it.readText() }
        )
    }

    // ---- 1. every catalogued tool is classified exactly once ----

    @Test
    fun everyToolHasExactlyOneManifestEntry() {
        val byTool = manifest.tools.groupBy { it.tool }
        val duplicates = byTool.filterValues { it.size > 1 }.keys
        assertTrue("duplicate manifest entries for: ${duplicates.sorted()}", duplicates.isEmpty())

        val missing = ToolDefinitions.all.map { it.function.name }.filterNot { byTool.containsKey(it) }
        assertTrue(
            "tool(s) with no coverage manifest entry: ${missing.sorted()}. " +
                "Add an entry to $MANIFEST_RESOURCE, then $REGENERATE_HINT",
            missing.isEmpty()
        )
    }

    // ---- 2. no manifest entry names a tool that no longer exists ----

    @Test
    fun noManifestEntryNamesAnUnknownTool() {
        val known = ToolDefinitions.all.map { it.function.name }.toSet()
        val unknown = manifest.tools.map { it.tool }.filterNot { it in known }
        assertTrue(
            "manifest entries for tools absent from ToolDefinitions.all (renamed or removed?): " +
                unknown.sorted(),
            unknown.isEmpty()
        )
    }

    @Test
    fun everyTierIsRecognised() {
        val bad = manifest.tools.filterNot { it.tier in TIERS }
        assertTrue(
            "unknown tier(s): " + bad.joinToString { "${it.tool}=${it.tier}" } + "; valid: $TIERS",
            bad.isEmpty()
        )
    }

    // ---- 3. JVM-tier entries name test classes that actually exist ----

    @Test
    fun jvmTierEntriesNameResolvableTestClasses() {
        val entries = manifest.tools.filter { it.tier == "UNIT" || it.tier == "ROBOLECTRIC" }
        val problems = entries.mapNotNull { entry ->
            when {
                entry.tests.isEmpty() -> "${entry.tool}: tier ${entry.tier} but no test classes listed"
                else -> entry.tests.firstOrNull { !classExists(it) }
                    ?.let { "${entry.tool}: test class '$it' does not resolve on the unit-test classpath" }
            }
        }
        assertTrue(problems.joinToString("\n"), problems.isEmpty())
    }

    // ---- 4. instrumented entries name source files that exist ----

    @Test
    fun instrumentedEntriesNameExistingSourceFiles() {
        val androidTestRoot = RepoPaths.file("app/src/androidTest/java")
        val problems = manifest.tools.filter { it.tier == "INSTRUMENTED" }.mapNotNull { entry ->
            when {
                entry.tests.isEmpty() -> "${entry.tool}: tier INSTRUMENTED but no test classes listed"
                else -> entry.tests.firstOrNull { fqcn ->
                    !File(androidTestRoot, fqcn.replace('.', '/') + ".kt").isFile
                }?.let { "${entry.tool}: no source file for '$it' under app/src/androidTest/java/" }
            }
        }
        assertTrue(problems.joinToString("\n"), problems.isEmpty())
    }

    // ---- 5. manual-only entries justify themselves ----

    @Test
    fun manualOnlyEntriesHaveReasonAndSteps() {
        val problems = manifest.tools.filter { it.tier == "MANUAL_ONLY" }.mapNotNull { entry ->
            when {
                entry.reason.isNullOrBlank() -> "${entry.tool}: MANUAL_ONLY needs a non-blank 'reason'"
                entry.reason.trim().length < 10 ->
                    "${entry.tool}: MANUAL_ONLY 'reason' is too short " +
                        "(${entry.reason.length} chars): \"${entry.reason}\""
                entry.manualSteps.isNullOrBlank() -> "${entry.tool}: MANUAL_ONLY needs non-blank 'manualSteps'"
                else -> null
            }
        }
        assertTrue(problems.joinToString("\n"), problems.isEmpty())
    }

    // ---- 6. the committed doc matches what the manifest renders ----

    @Test
    fun generatedDocMatchesCommittedCopy() {
        val expected = renderMarkdown(manifest)
        val doc = RepoPaths.file(DOC_PATH)

        if (System.getProperty(UPDATE_PROPERTY) == "true") {
            doc.parentFile.mkdirs()
            doc.writeText(expected)
            return
        }

        assertTrue(
            "$DOC_PATH does not exist yet — $REGENERATE_HINT",
            doc.isFile
        )
        assertEquals(
            "$DOC_PATH is out of date with $MANIFEST_RESOURCE — $REGENERATE_HINT",
            expected,
            doc.readText()
        )
    }

    // ---- rendering ----

    private fun renderMarkdown(manifest: CoverageManifest): String {
        val entries = manifest.tools.sortedBy { it.tool }
        val byTier = TIERS.associateWith { tier -> entries.count { it.tier == tier } }

        return buildString {
            appendLine("<!--")
            appendLine("  GENERATED FILE — DO NOT EDIT BY HAND.")
            appendLine("  Source of truth: app/src/test/resources/feature-test-coverage.json")
            appendLine("  Regenerate with: ./gradlew :app:testDebugUnitTest -PupdateCoverageDocs=true")
            appendLine("  Guarded by FeatureCoverageManifestTest, which fails CI on drift.")
            appendLine("-->")
            appendLine()
            appendLine("# Feature Test Coverage")
            appendLine()
            appendLine(
                "Every tool the assistant can call is listed below, together with how it is " +
                    "verified. Tools are the app's feature surface, so this table answers " +
                    "\"which features are tested, and which are not?\"."
            )
            appendLine()
            appendLine("## Summary")
            appendLine()
            appendLine("| Tier | Tools | What it means |")
            appendLine("|---|---:|---|")
            TIERS.forEach { tier ->
                appendLine("| `$tier` | ${byTier[tier]} | ${TIER_MEANING.getValue(tier)} |")
            }
            appendLine("| **Total** | **${entries.size}** | |")
            appendLine()
            val automated = entries.count { it.tier != "MANUAL_ONLY" }
            appendLine(
                "**$automated of ${entries.size}** tools are covered by an automated test; " +
                    "the remaining ${entries.size - automated} are manual-QA-only with a recorded reason."
            )
            appendLine()

            Category.entries.forEach { category ->
                val inCategory = entries.filter { ToolCategories.classify(it.tool) == category }
                if (inCategory.isEmpty()) return@forEach
                appendLine("## ${CATEGORY_TITLE.getValue(category)}")
                appendLine()
                appendLine("| Tool | Tier | Tests / reason |")
                appendLine("|---|---|---|")
                inCategory.forEach { entry ->
                    appendLine("| `${entry.tool}` | `${entry.tier}` | ${detailCell(entry)} |")
                }
                appendLine()
            }

            appendLine("## Device and Android-version coverage")
            appendLine()
            appendLine(
                "The tiers above say *whether* a feature is tested. This says *where*."
            )
            appendLine()
            appendLine("| Axis | Coverage |")
            appendLine("|---|---|")
            appendLine(
                "| Android versions (JVM) | `ROBOLECTRIC` tests run under `@Config(sdk = [...])`, " +
                    "typically API 30/33/34, so version-branching logic is covered in seconds " +
                    "without an emulator. |"
            )
            appendLine(
                "| Android versions (emulator) | Nightly `instrumented-full` matrix: API 30 " +
                    "(minSdk), 33, 34, 35, 36. Per-PR smoke runs API 34 only. |"
            )
            appendLine(
                "| OEM behaviour | **Not covered.** No emulator reproduces Samsung/Xiaomi battery " +
                    "managers killing overlay services and background agents — this app's largest " +
                    "real-world risk. Firebase Test Lab on physical devices is the only path; " +
                    "parked pending a GCP project (see `docs/TESTING_PLAN.md`). |"
            )
            appendLine()
            appendLine("### Instrumented suite")
            appendLine()
            appendLine(
                "`app/src/androidTest` covers app-level flows rather than individual tools: " +
                    "launch routing (`SmokeLaunchTest`), a chat round-trip against a mock LLM " +
                    "(`ChatRoundTripTest`), settings persistence (`SettingsFlowTest`) and the " +
                    "assistive ball overlay (`AssistiveBallTest`)."
            )
            appendLine()
            appendLine(
                "`AccessibilityServiceTest` is `@Ignore`d. Binding `GotchaAccessibilityService` " +
                    "reliably fails while the app is under self-instrumentation, even with the " +
                    "process warm and after waiting 45s; the identical sequence binds in 0-5s via " +
                    "plain `adb shell` outside instrumentation. That is a platform rough edge in " +
                    "`AccessibilityManagerService`, not a bug in the service — which is why every " +
                    "accessibility-dependent tool above is `MANUAL_ONLY`. A clean run reports " +
                    "5 passed / 1 skipped."
            )
            appendLine()
            appendLine("## Manual QA checklist")
            appendLine()
            val manual = entries.filter { it.tier == "MANUAL_ONLY" }
            if (manual.isEmpty()) {
                appendLine("_Nothing is manual-only — every tool has an automated test._")
            } else {
                appendLine(
                    "Run through this before a release. Each item is a tool with no automated " +
                        "coverage, so this list is the pre-release manual test script."
                )
                appendLine()
                manual.forEach { entry ->
                    appendLine("- [ ] **`${entry.tool}`** — ${entry.manualSteps}")
                    appendLine("  <br>_Why not automated:_ ${entry.reason}")
                }
            }
        }
    }

    private fun detailCell(entry: ToolCoverage): String {
        val parts = mutableListOf<String>()
        if (entry.tests.isNotEmpty()) {
            parts += entry.tests.joinToString(", ") { "`${it.substringAfterLast('.')}`" }
        }
        entry.notes?.takeIf { it.isNotBlank() }?.let { parts += it }
        entry.reason?.takeIf { it.isNotBlank() }?.let { parts += it }
        return parts.joinToString(" — ").ifEmpty { "—" }
    }

    // ---- helpers ----

    private fun classExists(fqcn: String): Boolean =
        runCatching { Class.forName(fqcn, false, javaClass.classLoader) }.isSuccess

    private companion object {
        const val MANIFEST_RESOURCE = "feature-test-coverage.json"
        const val DOC_PATH = "docs/FEATURE_TEST_COVERAGE.md"
        const val UPDATE_PROPERTY = "gotcha.coverage.update"
        const val REGENERATE_HINT =
            "regenerate with ./gradlew :app:testDebugUnitTest -PupdateCoverageDocs=true"

        val TIERS = listOf("UNIT", "ROBOLECTRIC", "INSTRUMENTED", "MANUAL_ONLY")

        val TIER_MEANING = mapOf(
            "UNIT" to "Plain JVM unit test — no Android framework needed.",
            "ROBOLECTRIC" to "JVM test against Robolectric's Android framework, often across several API levels.",
            "INSTRUMENTED" to "Runs on a real device or emulator (`app/src/androidTest`).",
            "MANUAL_ONLY" to "No automated test — verified by hand, see the checklist below."
        )

        val CATEGORY_TITLE = mapOf(
            Category.FOREGROUND to "Foreground tools (act on the screen)",
            Category.BACKGROUND to "Background tools (change device or account state)",
            Category.INFO to "Info tools (read-only)"
        )
    }
}
