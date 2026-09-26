package com.gotcha.data

import com.gotcha.agent.ComposerAttachment
import com.gotcha.llm.ChatMessage
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The lossless chat backup ("Back up chat" / "Back up all chats", issue #83):
 * whole [ChatSession]s, exactly as they are stored, wrapped with a format tag
 * and a version so an import can tell a backup from any other JSON and refuse
 * one written by a newer Gotcha than it understands.
 *
 * Versioning: add fields with defaults and keep [VERSION]; bump it only for a
 * change an older reader would get wrong, and teach [ChatImporter] the old one.
 */
@Serializable
data class ChatArchive(
    val format: String = FORMAT,
    val version: Int = VERSION,
    val exportedAt: Long,
    val appVersion: String = "",
    /** False when images were left out of the backup; see [withoutImages]. */
    val includesImages: Boolean = true,
    val sessions: List<ChatSession>
) {
    companion object {
        const val FORMAT = "gotcha-chats"
        const val VERSION = 1

        /** File extension the backup is saved with, so it reads as Gotcha's. */
        const val FILE_SUFFIX = ".gotcha.json"

        val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

        fun encode(archive: ChatArchive): String = json.encodeToString(serializer(), archive)

        /**
         * [session] with every image taken out: the vision parts of its LLM
         * history, the screenshots and picked photos of its transcript. Images
         * are most of a backup's size and can show more than the words do, so
         * the export asks before including them.
         */
        fun withoutImages(session: ChatSession): ChatSession = session.copy(
            messages = session.messages.map(::withoutImageParts),
            displayMessages = session.displayMessages.map { ui ->
                ui.copy(
                    imageBase64 = null,
                    attachments = ui.attachments.filterNot { it is ComposerAttachment.Image }
                )
            }
        )

        private fun withoutImageParts(message: ChatMessage): ChatMessage {
            val parts = message.content as? JsonArray ?: return message
            val kept = parts.filterNot { part ->
                ((part as? JsonObject)?.get("type") as? JsonPrimitive)?.content == "image_url"
            }
            return if (kept.size == parts.size) message else message.copy(content = JsonArray(kept))
        }
    }
}
