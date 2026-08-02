package com.gotcha.audio

import org.junit.Assert.assertEquals
import org.junit.Test

class SpeechTextSanitizerTest {

    @Test
    fun `strips simple emoji and trailing whitespace`() {
        assertEquals("Hello brother", SpeechTextSanitizer.stripEmoji("Hello brother 😊"))
    }

    @Test
    fun `strips emoji in the middle of a sentence`() {
        assertEquals("I am ready to help.", SpeechTextSanitizer.stripEmoji("I am ready 🙌 to help."))
    }

    @Test
    fun `strips multi-codepoint emoji with variation selectors and ZWJ`() {
        // Heavy check mark + variation selector, and a ZWJ family emoji.
        assertEquals("Done", SpeechTextSanitizer.stripEmoji("Done ✔️"))
        assertEquals("Family", SpeechTextSanitizer.stripEmoji("Family 👨‍👩‍👦"))
    }

    @Test
    fun `strips dingbats and misc symbol blocks`() {
        assertEquals("Great job", SpeechTextSanitizer.stripEmoji("Great job ✅⭐"))
    }

    @Test
    fun `leaves non-emoji text (including Hindi) untouched`() {
        val hindi = "हेलो भाई! मैं ठीक हूँ, आप कैसे हो?"
        assertEquals(hindi, SpeechTextSanitizer.stripEmoji(hindi))
    }

    @Test
    fun `all-emoji input collapses to blank`() {
        assertEquals("", SpeechTextSanitizer.stripEmoji("😊🙌✨"))
    }

    // ---- sanitize() ----

    @Test
    fun `sanitize leaves plain text untouched`() {
        assertEquals("Hello world", SpeechTextSanitizer.sanitize("Hello world"))
    }

    @Test
    fun `sanitize strips bold italic and strikethrough`() {
        assertEquals(
            "bold and italic and struck",
            SpeechTextSanitizer.sanitize("**bold** and *italic* and ~~struck~~")
        )
    }

    @Test
    fun `sanitize strips underscore bold and italic`() {
        assertEquals(
            "bold and italic",
            SpeechTextSanitizer.sanitize("__bold__ and _italic_")
        )
    }

    @Test
    fun `sanitize strips heading prefixes at every depth`() {
        assertEquals("Title", SpeechTextSanitizer.sanitize("# Title"))
        assertEquals("Sub", SpeechTextSanitizer.sanitize("### Sub"))
        assertEquals("Deep", SpeechTextSanitizer.sanitize("###### Deep"))
    }

    @Test
    fun `sanitize strips blockquote markers`() {
        assertEquals("quote", SpeechTextSanitizer.sanitize("> quote"))
    }

    @Test
    fun `sanitize removes fenced code blocks`() {
        // Whitespace-run collapse reduces the gap the removed fence left behind.
        assertEquals(
            "before after",
            SpeechTextSanitizer.sanitize("before ```ls -la\npwd``` after")
        )
    }

    @Test
    fun `sanitize unwraps inline code`() {
        assertEquals("run ls now", SpeechTextSanitizer.sanitize("run `ls` now"))
    }

    @Test
    fun `sanitize replaces bare URLs with link`() {
        assertEquals(
            "see link for info",
            SpeechTextSanitizer.sanitize("see https://x.com/a for info")
        )
        assertEquals("go to link", SpeechTextSanitizer.sanitize("go to www.example.com"))
    }

    @Test
    fun `sanitize strips markdown link syntax keeping label text`() {
        assertEquals(
            "click here for details",
            SpeechTextSanitizer.sanitize("click [here](https://x.com) for details")
        )
    }

    @Test
    fun `sanitize converts a dash bullet block to ordinal sentences`() {
        assertEquals(
            "First, Buy milk. Second, Walk dog. Third, Call mom.",
            SpeechTextSanitizer.sanitize("- Buy milk\n- Walk dog\n- Call mom")
        )
    }

    @Test
    fun `sanitize converts mixed bullet characters in one block`() {
        assertEquals(
            "First, a. Second, b. Third, c.",
            SpeechTextSanitizer.sanitize("* a\n- b\n+ c")
        )
    }

    @Test
    fun `sanitize handles bullet blocks separated by blank lines independently`() {
        val input = "Intro\n- one\n- two\n\nAfter\n- three"
        val expected = "Intro First, one. Second, two. After First, three."
        assertEquals(expected, SpeechTextSanitizer.sanitize(input))
    }

    @Test
    fun `sanitize uses Next for items beyond the twelfth ordinal`() {
        val input = (1..14).joinToString("\n") { "- item $it" }
        val once = SpeechTextSanitizer.sanitize(input)
        // First twelve get ordinal words, the rest fall back to "Next".
        assertEquals(
            "First, item 1. Second, item 2. Third, item 3. Fourth, item 4. Fifth, item 5. " +
                "Sixth, item 6. Seventh, item 7. Eighth, item 8. Ninth, item 9. Tenth, item 10. " +
                "Eleventh, item 11. Twelfth, item 12. Next, item 13. Next, item 14.",
            once
        )
    }

    @Test
    fun `sanitize leaves existing numbered lists untouched`() {
        // Numbered lists already have structure — only bullet chars get rewritten.
        assertEquals(
            "1. foo 2. bar 3. baz",
            SpeechTextSanitizer.sanitize("1. foo\n2. bar\n3. baz")
        )
    }

    @Test
    fun `sanitize does not treat dashes without trailing space as bullets`() {
        // "-item" without a space is not a markdown bullet.
        assertEquals(
            "sum a-b is positive",
            SpeechTextSanitizer.sanitize("sum a-b is positive")
        )
    }

    @Test
    fun `sanitize collapses multi-character punctuation`() {
        assertEquals("Stop!", SpeechTextSanitizer.sanitize("Stop!!!"))
        assertEquals("Really?", SpeechTextSanitizer.sanitize("Really???"))
        // Single ? or ! don't trigger collapse (only runs of two or more).
        assertEquals("Wait?!", SpeechTextSanitizer.sanitize("Wait?!"))
        assertEquals("Wow!", SpeechTextSanitizer.sanitize("Wow!!!!!!"))
    }

    @Test
    fun `sanitize normalizes unicode ellipsis to three dots`() {
        assertEquals("wait...", SpeechTextSanitizer.sanitize("wait…"))
    }

    @Test
    fun `sanitize collapses whitespace runs including newlines`() {
        assertEquals("a b c", SpeechTextSanitizer.sanitize("a   b\n\nc"))
        assertEquals("a b", SpeechTextSanitizer.sanitize("a\t\tb"))
    }

    @Test
    fun `sanitize strips emoji like stripEmoji`() {
        assertEquals("Hello", SpeechTextSanitizer.sanitize("Hello 😊"))
        assertEquals("", SpeechTextSanitizer.sanitize("😊🙌✨"))
    }

    @Test
    fun `sanitize handles a combined real-world assistant reply`() {
        val input = "# Steps\n- **First**: `ls -la`\n- See https://x.com\nDone ✅"
        assertEquals(
            "Steps First, First: ls -la. Second, See link. Done",
            SpeechTextSanitizer.sanitize(input)
        )
    }

    @Test
    fun `sanitize is idempotent`() {
        val input = "**bold** with `code` and - bullets\n- more\n\nsee https://x.com today!"
        val once = SpeechTextSanitizer.sanitize(input)
        val twice = SpeechTextSanitizer.sanitize(once)
        assertEquals(once, twice)
    }

    @Test
    fun `sanitize handles empty input`() {
        assertEquals("", SpeechTextSanitizer.sanitize(""))
    }

    @Test
    fun `sanitize leaves non-Latin scripts untouched`() {
        val hindi = "हेलो भाई! मैं ठीक हूँ, आप कैसे हो?"
        assertEquals(hindi, SpeechTextSanitizer.sanitize(hindi))
    }

    @Test
    fun `sanitize does not touch snake_case identifiers with underscores`() {
        // `foo_bar_baz` is not italic — underscores are inside a word.
        assertEquals(
            "see foo_bar_baz here",
            SpeechTextSanitizer.sanitize("see foo_bar_baz here")
        )
    }

    @Test
    fun `sanitize preserves trailing sentence periods from prose`() {
        // Non-bullet text already has its own punctuation — don't add extra.
        assertEquals("Hello there.", SpeechTextSanitizer.sanitize("Hello there."))
    }
}
