package com.gotcha.agent

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.gotcha.data.LlmProvider
import com.gotcha.data.Settings
import com.gotcha.data.SettingsRepository
import com.gotcha.llm.ChatMessage
import com.gotcha.testsupport.FakeAndroidKeyStore
import com.gotcha.testsupport.FakeFilesProvider
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper

/**
 * Attachment behaviour of [ChatViewModel]: the composer queue, the message built
 * for the LLM, the transcript label, and the send gate. The LLM base URL points at a
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
        FakeFilesProvider.files.clear()
        Robolectric.buildContentProvider(FakeFilesProvider::class.java).create(FakeFilesProvider.AUTHORITY)
    }

    private fun doc(name: String = "report.pdf", text: String = "PDF BODY"): ComposerAttachment.Document =
        ComposerAttachment.Document(
            id = name,
            attachment = Attachment(name, "application/pdf", text.length.toLong(), text, pageCount = 2)
        )

    private fun image(id: String, base64: String = "IMG-$id"): ComposerAttachment.Image =
        ComposerAttachment.Image(id = id, name = "$id.jpg", base64 = base64)

    private fun textFile(key: String, body: String): Uri =
        FakeFilesProvider.add(key, "$key.txt", "text/plain", body.toByteArray())

    private fun pngFile(key: String): Uri {
        val bitmap = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
        val bytes = java.io.ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return FakeFilesProvider.add(key, "$key.png", "image/png", bytes.toByteArray())
    }

    private fun pending(): List<ComposerAttachment> = viewModel.uiState.value.pendingAttachments

    /** Image data URIs of [message]'s image parts, in order. */
    private fun imageUrls(message: ChatMessage): List<String> =
        (message.content as JsonArray)
            .map { it.jsonObject }
            .filter { it["type"]?.jsonPrimitive?.content == "image_url" }
            .map { it["image_url"]!!.jsonObject["url"]!!.jsonPrimitive.content }

    /** Drains the main looper until [condition] holds (the pick loads on Dispatchers.IO). */
    private fun waitUntil(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline && !condition()) {
            ShadowLooper.idleMainLooper()
            Thread.sleep(10)
        }
        ShadowLooper.idleMainLooper()
    }

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
        viewModel.sendMessage("summarize", listOf(doc("report.pdf", "PDF BODY")))
        ShadowLooper.idleMainLooper()

        val userBubble = viewModel.uiState.value.messages.first { it.kind == MessageKind.USER }
        assertEquals("summarize", userBubble.text)
        assertEquals(listOf("report.pdf"), userBubble.attachments.map { it.name })

        val last = engineHistory().last()
        assertTrue(last.textContent.contains("[Attached file: report.pdf (application/pdf, 2 pages)]"))
        assertTrue(last.textContent.contains("PDF BODY"))
    }

    @Test
    fun `sendMessage with only a document shows the document placeholder`() {
        viewModel.sendMessage("", listOf(doc("notes.txt", "hello")))
        ShadowLooper.idleMainLooper()

        val userBubble = viewModel.uiState.value.messages.first { it.kind == MessageKind.USER }
        assertEquals("(document attached)", userBubble.text)
        assertEquals(listOf("notes.txt"), userBubble.attachments.map { it.name })
    }

    @Test
    fun `sendMessage is a no-op when input and attachments are all empty`() {
        val before = viewModel.uiState.value.messages.size

        viewModel.sendMessage("   ", emptyList())
        ShadowLooper.idleMainLooper()

        assertEquals(before, viewModel.uiState.value.messages.size)
        assertTrue(engineHistory().isEmpty())
    }

    @Test
    fun `sendMessage sends every image as its own image part, in order`() {
        viewModel.sendMessage("compare these", listOf(image("a"), image("b"), image("c")))
        ShadowLooper.idleMainLooper()

        val last = engineHistory().last()
        assertEquals("compare these", last.textContent)
        assertEquals(
            listOf("IMG-a", "IMG-b", "IMG-c").map { "data:image/jpeg;base64,$it" },
            imageUrls(last)
        )
        val userBubble = viewModel.uiState.value.messages.first { it.kind == MessageKind.USER }
        assertEquals(listOf("a", "b", "c"), userBubble.attachments.map { it.id })
    }

    @Test
    fun `sendMessage with documents and images keeps every document in the first text part`() {
        viewModel.sendMessage(
            "",
            listOf(doc("one.pdf", "FIRST BODY"), image("x"), doc("two.pdf", "SECOND BODY"))
        )
        ShadowLooper.idleMainLooper()

        val last = engineHistory().last()
        val text = last.textContent
        assertTrue(text.contains("[Attached file: one.pdf") && text.contains("FIRST BODY"))
        assertTrue(text.contains("[Attached file: two.pdf") && text.contains("SECOND BODY"))
        assertTrue("documents keep their order", text.indexOf("one.pdf") < text.indexOf("two.pdf"))
        assertEquals(listOf("data:image/jpeg;base64,IMG-x"), imageUrls(last))

        val userBubble = viewModel.uiState.value.messages.first { it.kind == MessageKind.USER }
        assertEquals("(files attached)", userBubble.text)
    }

    @Test
    fun `addAttachments queues files in picked order and keeps earlier ones`() {
        viewModel.addAttachments(listOf(textFile("first", "ONE"), textFile("second", "TWO")))
        waitUntil { pending().size == 2 }
        viewModel.addAttachments(listOf(textFile("third", "THREE")))
        waitUntil { pending().size == 3 }

        assertEquals(listOf("first.txt", "second.txt", "third.txt"), pending().map { it.name })
        val third = pending().last() as ComposerAttachment.Document
        assertEquals("THREE", third.attachment.text)
        // Picking files never sends anything.
        assertTrue(engineHistory().isEmpty())
        assertTrue(viewModel.uiState.value.messages.none { it.kind == MessageKind.USER })
    }

    @Test
    fun `addAttachments reads images through the image pipeline`() {
        viewModel.addAttachments(listOf(pngFile("photo"), textFile("notes", "NOTES")))
        waitUntil { pending().size == 2 }

        val first = pending().first()
        assertTrue("expected an image, got $first", first is ComposerAttachment.Image)
        assertEquals("photo.png", first.name)
        assertTrue((first as ComposerAttachment.Image).base64.isNotBlank())
        assertTrue(pending()[1] is ComposerAttachment.Document)
    }

    @Test
    fun `addAttachments skips an unreadable file with an error and keeps the rest`() {
        val missing = Uri.parse("content://com.gotcha.missing.provider/attachments/missing.pdf")

        viewModel.addAttachments(listOf(textFile("good", "GOOD"), missing))

        // No work ran synchronously on the calling thread.
        assertEquals(0, viewModel.uiState.value.messages.size)
        waitUntil { viewModel.uiState.value.messages.isNotEmpty() }

        assertEquals(listOf("good.txt"), pending().map { it.name })
        val error = viewModel.uiState.value.messages.lastOrNull()
        assertEquals(MessageKind.ERROR, error?.kind)
        assertTrue(error?.text?.contains("Could not read that file") == true)
    }

    @Test
    fun `addAttachments stops at the per-message limit with an error`() {
        val nine = (1..9).map { image("i$it") }
        viewModel.setAttachments(nine)

        viewModel.addAttachments(listOf(textFile("tenth", "10"), textFile("eleventh", "11"), textFile("twelfth", "12")))
        waitUntil { viewModel.uiState.value.messages.isNotEmpty() }

        assertEquals(ComposerAttachment.MAX_PER_MESSAGE, pending().size)
        assertEquals("tenth.txt", pending().last().name)
        val error = viewModel.uiState.value.messages.last()
        assertEquals(MessageKind.ERROR, error.kind)
        assertTrue(error.text, error.text.contains("up to 10 files") && error.text.contains("skipped 2 files"))
    }

    @Test
    fun `addAttachments rejects documents past the combined text budget`() {
        val big = "x".repeat(ComposerAttachment.MAX_TOTAL_DOCUMENT_CHARS - 10)
        viewModel.setAttachments(listOf(doc("big.pdf", big)))

        viewModel.addAttachments(listOf(textFile("more", "this pushes it over the budget")))
        waitUntil { viewModel.uiState.value.messages.isNotEmpty() }

        assertEquals(listOf("big.pdf"), pending().map { it.name })
        assertTrue(viewModel.uiState.value.messages.last().text.contains("too long to send together"))
    }

    @Test
    fun `removeAttachment drops only that file and keeps the order`() {
        viewModel.setAttachments(listOf(image("a"), doc("b.pdf"), image("c")))

        viewModel.removeAttachment("b.pdf")

        assertEquals(listOf("a", "c"), pending().map { it.id })
    }

    @Test
    fun `editMessage re-sends the original attachments when none are given`() {
        viewModel.sendMessage("original question", listOf(doc("doc.pdf", "DOC BODY"), image("p")))
        waitForRunToFinish()
        val targetId = viewModel.uiState.value.messages.first { it.kind == MessageKind.USER }.id

        viewModel.editMessage(targetId = targetId, newText = "edited question", attachments = null)
        waitForRunToFinish()

        val last = engineHistory().last()
        assertTrue(last.textContent.contains("edited question"))
        assertTrue(
            "edited message must keep the original attachment",
            last.textContent.contains("[Attached file: doc.pdf")
        )
        assertTrue("edited message must keep the extracted body", last.textContent.contains("DOC BODY"))
        assertEquals(listOf("data:image/jpeg;base64,IMG-p"), imageUrls(last))
    }

    @Test
    fun `editMessage with an empty list drops the attachments`() {
        viewModel.sendMessage("original question", listOf(image("p")))
        waitForRunToFinish()
        val targetId = viewModel.uiState.value.messages.first { it.kind == MessageKind.USER }.id

        viewModel.editMessage(targetId = targetId, newText = "just text now", attachments = emptyList())
        waitForRunToFinish()

        val last = engineHistory().last()
        assertEquals("just text now", last.textContent)
        assertEquals(0, last.imageCount)
    }
}
