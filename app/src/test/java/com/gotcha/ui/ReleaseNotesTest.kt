package com.gotcha.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** [releaseNotesHeadlines] is what the About screen shows before "Show all release notes" (issue #70). */
class ReleaseNotesTest {

    @Test
    fun `changelog notes shorten to headings and bold leads`() {
        val notes = """
            ### Added
            - **Podcast generation.** Gotcha can now turn text into listenable audio. Ask
              for a topic as a podcast (`synthesize_podcast`).
            - **PDF editing.** A new `pdf_edit` tool reshapes PDFs.
            ### Fixed
            - **Termux commands** run with Termux closed.
        """.trimIndent()
        assertEquals(
            """
                ### Added
                - **Podcast generation.**
                - **PDF editing.**
                ### Fixed
                - **Termux commands**
            """.trimIndent(),
            releaseNotesHeadlines(notes)
        )
    }

    @Test
    fun `an item without a bold lead keeps the notes whole`() {
        val notes = "### Added\n- **Podcasts.** Audio from text.\n- Faster startup."
        assertNull(releaseNotesHeadlines(notes))
    }

    @Test
    fun `notes with nothing to hide are not shortened`() {
        assertNull(releaseNotesHeadlines("### Fixed\n- **Crash on launch**\n- **Wrong date**"))
    }

    @Test
    fun `plain text notes are not shortened`() {
        assertNull(releaseNotesHeadlines("Bug fixes"))
    }
}
