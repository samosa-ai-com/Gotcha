package com.gotcha.data

import android.content.Context
import com.gotcha.llm.ChatMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Persists the single conversation (LLM-shaped messages, including tool
 * calls/results) as JSON in the app sandbox so history survives restarts.
 */
class ChatHistoryRepository(context: Context) {

    private val file = File(context.filesDir, "chat_history.json")
    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = ListSerializer(ChatMessage.serializer())

    suspend fun load(): List<ChatMessage> = withContext(Dispatchers.IO) {
        if (!file.exists()) return@withContext emptyList()
        try {
            json.decodeFromString(serializer, file.readText())
        } catch (e: Exception) {
            emptyList() // corrupt history is dropped rather than crashing
        }
    }

    suspend fun save(messages: List<ChatMessage>) = withContext(Dispatchers.IO) {
        try {
            file.writeText(json.encodeToString(serializer, messages))
        } catch (_: Exception) {
            // Persistence is best-effort.
        }
    }

    suspend fun clear() = withContext(Dispatchers.IO) {
        file.delete()
    }
}
