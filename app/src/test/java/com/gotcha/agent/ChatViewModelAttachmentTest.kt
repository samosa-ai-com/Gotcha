package com.gotcha.agent

import android.app.Application
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.gotcha.data.LlmProvider
import com.gotcha.data.Settings
import com.gotcha.data.SettingsRepository
import com.gotcha.llm.ChatMessage
import com.gotcha.testsupport.FakeAndroidKeyStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper

/**
 * Document-attachment behaviour of [ChatViewModel]: the message built for the
 * LLM, the transcript label, and the send gate. The LLM base URL points at a
 * port that refuses connections so the run fails fast and the test stays hermetic.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ChatViewModelAttachmentTest {

    private val application: Application = ApplicationProvider.getApplicationContext()
    private lateinit var settingsRepository: SettingsRepository
    private lateinit var viewModel: ChatViewModel

    @Before
    fun setUp() {
        FakeAndroidKeyStore.setUp()
        settingsRepository = SettingsRepository(application)
        settingsRepository.save(
            Settings(
                provider = LlmProvider.OPENAI_COMPATIBLE,
                apiKey = "test-key",
                baseUrl = "http://127.0.0.1:1/v1"
            )
        )
        viewModel = ChatViewModel(application)
        ShadowLooper.idleMainLooper()
    }

    private fun doc(name: String = "report.pdf", text: String = "PDF BODY"): Attachment =
        Attachment(name, "application/pdf", text.length.toLong(), text, pageCount = 2)

    /** Reads the live engine history without hitting the network. */
    private fun engineHistory(): List<ChatMessage> {
        val field = ChatViewModel::class.java.getDeclaredField("agentEngine")
            .apply { isAccessible = true }
        return (field.get(viewModel) as AgentEngine).history.toList()
    }

    /**
     * Drains the main looper until the run in flight finishes (the refused
     * connection fails fast), so a follow-up action is not swallowed by the
     * busy gate.
     */
    private fun waitForRunToFinish() {
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline && viewModel.uiState.value.isBusy) {
            ShadowLooper.idleMainLooper()
            Thread.sleep(10)
        }
        ShadowLooper.idleMainLooper()
    }

    @Test
    fun `sendMessage with a document builds a document message in history`() {
        viewModel.sendMessage("summarize", null, doc("report.pdf", "PDF BODY"))
        ShadowLooper.idleMainLooper()

        val userBubble = viewModel.uiState.value.messages.first { it.kind == MessageKind.USER }
        assertEquals("summarize", userBubble.text)
        assertEquals("report.pdf", userBubble.attachment?.name)

        val last = engineHistory().last()
        assertTrue(last.textContent.contains("[Attached file: report.pdf (application/pdf, 2 pages)]"))
        assertTrue(last.textContent.contains("PDF BODY"))
    }

    @Test
    fun `sendMessage with only a document shows the document placeholder`() {
        viewModel.sendMessage("", null, doc("notes.txt", "hello"))
        ShadowLooper.idleMainLooper()

        val userBubble = viewModel.uiState.value.messages.first { it.kind == MessageKind.USER }
        assertEquals("(document attached)", userBubble.text)
        assertEquals("notes.txt", userBubble.attachment?.name)
    }

    @Test
    fun `sendMessage is a no-op when input and attachments are all empty`() {
        val before = viewModel.uiState.value.messages.size

        viewModel.sendMessage("   ", null, null)
        ShadowLooper.idleMainLooper()

        assertEquals(before, viewModel.uiState.value.messages.size)
        assertTrue(engineHistory().isEmpty())
    }

    @Test
    fun `pickContent parses off the caller thread and delivers on pickResults`() {
        // A content URI with no provider behind it: the stream can't open, so the
        // pick resolves to null and a visible error bubble is appended. The point
        // is the plumbing — no work may run on the calling thread, and the result
        // arrives asynchronously via pickResults.
        val uri = Uri.parse("content://com.gotcha.missing.provider/attachments/missing.pdf")

        viewModel.pickContent(uri)

        // No work ran synchronously on the calling thread.
        assertEquals(0, viewModel.uiState.value.messages.size)

        val deadline = System.currentTimeMillis() + 5_000
        var result: PickedFile? = null
        val collector = CoroutineScope(Dispatchers.Default).launch {
            result = withTimeoutOrNull(5_000) { viewModel.pickResults.first() }
        }
        while (System.currentTimeMillis() < deadline && result == null) {
            ShadowLooper.idleMainLooper()
            Thread.sleep(10)
        }
        collector.cancel()

        assertNull("pick should resolve to null, got $result", result)
        // The failed pick is never silent: an error bubble explains it in the chat.
        val error = viewModel.uiState.value.messages.lastOrNull()
        assertEquals(MessageKind.ERROR, error?.kind)
        assertTrue(error?.text?.contains("Could not read that file") == true)
    }

    @Test
    fun `editMessage re-sends the original document when none is picked`() {
        viewModel.sendMessage("original question", null, doc("doc.pdf", "DOC BODY"))
        waitForRunToFinish()
        val targetId = viewModel.uiState.value.messages.first { it.kind == MessageKind.USER }.id

        viewModel.editMessage(targetId = targetId, newText = "edited question", imageBase64 = null, attachment = null)
        waitForRunToFinish()

        val last = engineHistory().last()
        assertTrue(last.textContent.contains("edited question"))
        assertTrue(
            "edited message must keep the original attachment",
            last.textContent.contains("[Attached file: doc.pdf")
        )
        assertTrue("edited message must keep the extracted body", last.textContent.contains("DOC BODY"))
    }
}
