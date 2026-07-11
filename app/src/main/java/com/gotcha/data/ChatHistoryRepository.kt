package com.gotcha.data

import android.content.Context
import com.gotcha.llm.ChatMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File

import kotlinx.serialization.Serializable
import java.util.UUID

@Serializable
data class ChatSession(
    val id: String,
    val title: String,
    val lastModified: Long,
    val messages: List<ChatMessage>,
    val tokenCount: Int = 0
)

/**
 * Persists multiple chat sessions as JSON files in a dedicated 'chats' directory.
 */
class ChatHistoryRepository(context: Context) {

    private val chatsDir = File(context.filesDir, "chats").apply { mkdirs() }
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
                } catch (e: Exception) {
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
        } catch (e: Exception) {
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
