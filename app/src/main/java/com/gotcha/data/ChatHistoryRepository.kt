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
    val agentMode: String? = null
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

    suspend fun saveSession(session: ChatSession) = withContext(Dispatchers.IO) {
        try {
            val file = File(chatsDir, "${session.id}.json")
            file.writeText(json.encodeToString(serializer, session.copy(lastModified = System.currentTimeMillis())))
        } catch (_: Exception) {
            // Best-effort
        }
    }

    suspend fun deleteSession(id: String) = withContext(Dispatchers.IO) {
        File(chatsDir, "$id.json").delete()
    }
}
