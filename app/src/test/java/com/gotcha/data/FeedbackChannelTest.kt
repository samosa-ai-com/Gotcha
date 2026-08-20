package com.gotcha.data

import com.gotcha.auth.ClaimReferralRequest
import com.gotcha.auth.ClaimReferralResponse
import com.gotcha.auth.MeResponse
import com.gotcha.auth.RegisterRequest
import com.gotcha.auth.RegisterResponse
import com.gotcha.auth.SamosaAuthApi
import com.gotcha.auth.SamosaUser
import com.gotcha.llm.ChatMessage
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FeedbackChannelTest {

    private val formUrl = "https://forms.example.com/viewform"
    private val entries = FeedbackChannel.FeedbackEntries(
        userId = "entry.1",
        appVersion = "entry.2",
        deviceModel = "entry.3",
        androidVersion = "entry.4",
        usageStats = "entry.5",
        chatLog = "entry.6"
    )

    @Test
    fun `builds a url with only provided prefill fields`() {
        val url = FeedbackChannel.buildFeedbackUrl(
            FeedbackPrefill(appVersion = "0.1.0", userId = "user-123", usageStats = "Runs: 1"),
            formUrl,
            entries
        )

        assertTrue(url, url.startsWith("$formUrl?usp=pp_url"))
        assertTrue(url, url.contains("entry.2=0.1.0"))
        assertTrue(url, url.contains("entry.1=user-123"))
        assertTrue(url, url.contains("entry.5=Runs%3A%201"))
        assertFalse(url, url.contains("entry.3="))
        assertFalse(url, url.contains("entry.6="))
    }

    @Test
    fun `blank form url yields a blank url`() {
        val url = FeedbackChannel.buildFeedbackUrl(
            FeedbackPrefill(appVersion = "x"),
            "",
            entries
        )

        assertEquals("", url)
    }

    @Test
    fun `url-encodes spaces and special characters`() {
        val url = FeedbackChannel.buildFeedbackUrl(
            FeedbackPrefill(chatLog = "line one\ntwo & three"),
            formUrl,
            entries
        )

        assertTrue(url, url.contains("entry.6=line%20one%0Atwo%20%26%20three"))
    }

    @Test
    fun `short chat log is included verbatim`() {
        val url = FeedbackChannel.buildFeedbackUrl(
            FeedbackPrefill(chatLog = "hello world"),
            formUrl,
            entries
        )

        assertTrue(url, url.contains("entry.6=hello%20world"))
    }

    @Test
    fun `oversized unicode chat log is fitted so the url stays under the cap`() {
        // Devanagari + emoji expand 9-12x when percent-encoded; a 12k-char log
        // would otherwise blow well past Google's prefilled-URL ceiling.
        val log = buildString {
            repeat(3000) {
                append("यह एक परीक्षण संदेश है नोशन मेरी पेजें नहीं खुल रही हैं 😊🚀\n")
            }
        }
        assertTrue(log.length > FeedbackChannel.DEFAULT_EXCERPT_CHARS)

        val url = FeedbackChannel.buildFeedbackUrl(
            FeedbackPrefill(
                appVersion = "0.1.0",
                deviceModel = "Pixel",
                androidVersion = "Android 16",
                userId = "user-123",
                usageStats = "Runs: 13",
                chatLog = log
            ),
            formUrl,
            entries
        )

        assertTrue(url, url.length <= FeedbackChannel.MAX_PREFILL_URL_LEN)
        assertTrue(url, url.contains("entry.6="))
        // The head of the log must survive the encoded-length fit.
        assertTrue(url, url.contains("%E0%A4%AF%E0%A4%B9"))
    }

    @Test
    fun `chat log is dropped when it cannot fit even a snippet`() {
        // A form URL already at the cap leaves no budget for the chat log.
        val longBase = "https://forms.example.com/" + "x".repeat(8000)
        val url = FeedbackChannel.buildFeedbackUrl(
            FeedbackPrefill(chatLog = "should be dropped"),
            longBase,
            entries
        )

        assertFalse(url, url.contains("entry.6="))
    }

    @Test
    fun `excerpt stays within maxChars even below the marker length`() {
        // maxChars < marker length used to drive head/tail negative; the guard
        // must fall back to a plain prefix instead of emitting a longer string.
        val messages = (1..200).map { ChatMessage(role = "user", content = JsonPrimitive("message $it")) }
        val excerpt = FeedbackChannel.chatLogExcerpt(messages, maxChars = 3)

        assertTrue(excerpt, excerpt.length <= 3)
        assertFalse(excerpt, excerpt.contains("truncated"))
    }

    @Test
    fun `session header includes id start date and message count`() {
        val session = ChatSession(
            id = "abc123",
            title = "Test",
            lastModified = 1_700_000_000_000L,
            messages = listOf(ChatMessage(role = "user", content = JsonPrimitive("hi")))
        )

        val header = FeedbackChannel.sessionHeader(session)

        assertTrue(header, header.contains("Session: abc123"))
        assertTrue(header, header.contains("Started: "))
        assertTrue(header, header.contains("Messages: 1"))
    }

    @Test
    fun `chat excerpt truncates from the middle within the char cap`() {
        val messages = (1..200).map { ChatMessage(role = "user", content = JsonPrimitive("message $it")) }
        val excerpt = FeedbackChannel.chatLogExcerpt(messages, maxChars = 200)

        assertTrue(excerpt, excerpt.length <= 200)
        assertTrue(excerpt, excerpt.contains("… truncated …"))
        assertTrue(excerpt, excerpt.startsWith("User: message 1"))
        assertTrue(excerpt, excerpt.endsWith("message 200"))
    }

    @Test
    fun `short chat is not truncated`() {
        val messages = listOf(ChatMessage(role = "user", content = JsonPrimitive("hello")))
        val excerpt = FeedbackChannel.chatLogExcerpt(messages, maxChars = 200)

        assertEquals("User: hello", excerpt)
    }

    @Test
    fun `blank and image-only messages are skipped`() {
        val messages = listOf(
            ChatMessage(role = "user", content = JsonPrimitive("what is on screen?")),
            ChatMessage(role = "assistant", content = null)
        )

        val excerpt = FeedbackChannel.chatLogExcerpt(messages, maxChars = 200)

        assertEquals("User: what is on screen?", excerpt)
    }

    @Test
    fun `resolves user id from the me endpoint`() = runBlocking {
        val api = fakeApi(id = "user-9")
        assertEquals("user-9", FeedbackChannel.resolveSamosaUserId("token", "a@b.com", api))
    }

    @Test
    fun `falls back to email when me returns no id`() = runBlocking {
        val api = fakeApi(id = "")
        assertEquals("a@b.com", FeedbackChannel.resolveSamosaUserId("token", "a@b.com", api))
    }

    @Test
    fun `returns null when not signed in`() = runBlocking {
        assertNull(FeedbackChannel.resolveSamosaUserId("", "a@b.com", fakeApi(id = "user-9")))
    }

    private fun fakeApi(id: String) = object : SamosaAuthApi {
        override suspend fun register(body: RegisterRequest): RegisterResponse = RegisterResponse()
        override suspend fun me(bearer: String): MeResponse =
            MeResponse(user = SamosaUser(id = id, email = "a@b.com"))
        override suspend fun claimReferral(
            bearer: String,
            body: ClaimReferralRequest
        ): ClaimReferralResponse = ClaimReferralResponse()
        override suspend fun logout(bearer: String) = Unit
    }
}
