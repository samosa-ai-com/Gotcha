package com.gotcha.data

import android.content.Context
import com.gotcha.agent.UiMessage
import com.gotcha.llm.ChatMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
data class ChatSession(
    val id: String,
    val title: String,
    val lastModified: Long,
    val messages: List<ChatMessage>,
    val tokenCount: Int = 0,
    /**
     * The verbatim on-screen transcript for this session. Persisted separately
     * from [messages] (the LLM-shaped history) so reopening a chat restores
     * exactly what was shown live, without lossy reconstruction. Reset on
     * history compaction so pre-compaction bubbles are intentionally dropped.
     */
    val displayMessages: List<UiMessage> = emptyList(),
    /** Persisted agent mode ("MONITOR"/"OPERATOR") so it survives app restarts. */
    val agentMode: String? = null,
    /**
     * Structured records of completed runs (the "share your moment" raw data),
     * newest last. Bounded at capture time so this can't grow unbounded.
     */
    val runSummaries: List<RunSummary> = emptyList(),
    /**
     * True for the chats seeded on first run to demonstrate what Gotcha can be
     * asked for. Marked in the drawer and above the transcript so a sample is
     * never mistaken for something the user said, but otherwise an ordinary
     * session: openable, continuable and deletable. Defaulted so every chat
     * written before samples existed decodes as a real one.
     */
    val isSample: Boolean = false
)

/**
 * Persists multiple chat sessions as JSON files in a dedicated directory
 * (default 'chats'; voice calls use a separate 'calls' directory so they
 * never show up in the main chat list).
 */
class ChatHistoryRepository internal constructor(private val chatsDir: File) {

    constructor(context: Context, dirName: String = "chats") :
        this(File(context.filesDir, dirName))

    init {
        chatsDir.mkdirs()
    }
    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = ChatSession.serializer()

    suspend fun listSessions(): List<ChatSession> = withContext(Dispatchers.IO) {
        chatsDir.listFiles()
            ?.filter { it.extension == "json" }
            ?.mapNotNull { file ->
                try {
                    val session = json.decodeFromString(serializer, file.readText())
                    // Only return metadata-light clone if desired, but we just return full for now
                    session
                } catch (_: Exception) {
                    null
                }
            }
            ?.sortedByDescending { it.lastModified }
            ?: emptyList()
    }

    suspend fun loadSession(id: String): ChatSession? = withContext(Dispatchers.IO) {
        val file = File(chatsDir, "$id.json")
        if (!file.exists()) return@withContext null
        try {
            json.decodeFromString(serializer, file.readText())
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Writes [session], stamping it as modified now. Pass [touch] = false to keep
     * the session's own [ChatSession.lastModified] — seeded sample chats carry
     * crafted timestamps that decide their order in the drawer, and a save-time
     * stamp would collapse them all into the same millisecond.
     */
    suspend fun saveSession(session: ChatSession, touch: Boolean = true) = withContext(Dispatchers.IO) {
        try {
            val file = File(chatsDir, "${session.id}.json")
            val toWrite =
                if (touch) session.copy(lastModified = System.currentTimeMillis()) else session
            file.writeText(json.encodeToString(serializer, toWrite))
        } catch (_: Exception) {
            // Best-effort
        }
    }

    suspend fun deleteSession(id: String) = withContext(Dispatchers.IO) {
        File(chatsDir, "$id.json").delete()
    }
}
