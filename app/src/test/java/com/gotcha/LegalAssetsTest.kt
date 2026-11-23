package com.gotcha

import com.gotcha.testsupport.RepoPaths
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Legal page and the first-launch consent dialog load their documents by
 * asset path, and a missing asset only shows up at runtime as a "Failed to
 * read asset" line in place of the text. These checks read the two screens'
 * sources, so a renamed or dropped document fails here instead.
 */
class LegalAssetsTest {

    private val screens = listOf(
        "com/gotcha/ui/LegalScreen.kt",
        "com/gotcha/LegalConsentDialog.kt"
    )

    private fun legalPaths(source: String): List<String> =
        Regex("\"(legal/[A-Za-z0-9-]+\\.md)\"").findAll(source).map { it.groupValues[1] }.toList()

    @Test
    fun `every legal document a screen loads is bundled and not empty`() {
        for (screen in screens) {
            val paths = legalPaths(RepoPaths.mainSource(screen))
            assertTrue("$screen loads no legal documents", paths.isNotEmpty())
            for (path in paths) {
                val asset = RepoPaths.file("app/src/main/assets/$path")
                assertTrue("$screen loads $path, which is not bundled", asset.isFile)
                assertTrue("$path is empty", asset.readText().isNotBlank())
            }
        }
    }

    @Test
    fun `both screens show the Samosa AIR terms`() {
        for (screen in screens) {
            assertTrue(
                "$screen does not show the Samosa AIR terms",
                "legal/samosa-air-terms.md" in legalPaths(RepoPaths.mainSource(screen))
            )
        }
    }
}
