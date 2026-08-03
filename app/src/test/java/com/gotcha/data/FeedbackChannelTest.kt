package com.gotcha.data

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
        override suspend fun me(bearer: String): SamosaUser = SamosaUser(id = id, email = "a@b.com")
        override suspend fun logout(bearer: String) = Unit
    }
}
