package com.gotcha.data

import com.gotcha.agent.MessageKind
import com.gotcha.agent.UiMessage
import com.gotcha.llm.ChatMessage
import kotlinx.serialization.json.JsonPrimitive

/**
 * The chats seeded into an empty chat list on first run.
 *
 * A fresh install opened on nothing at all, so the one thing a first-time user
 * could not find out was what Gotcha is for. These two transcripts answer that
 * by showing it: a device action with a follow-up (Operator) and a question
 * about the screen (Monitor) — the two halves of the agent selector.
 *
 * They are ordinary sessions, not a special screen: openable, continuable and
 * deletable like any other, and marked [ChatSession.isSample] so the drawer and
 * the transcript header can say where they came from.
 */
internal object SampleChats {

    /** Stable ids: re-seeding can only ever overwrite these, never fork copies. */
    const val DEVICE_ACTION_ID = "sample-device-action"
    const val SCREEN_QA_ID = "sample-screen-qa"

    /**
     * Both samples, oldest first, timestamped just before [now] so the first
     * chat the user actually starts sorts above them in the drawer.
     */
    fun all(now: Long = System.currentTimeMillis()): List<ChatSession> = listOf(
        deviceAction(now - TWO_MINUTES),
        screenQa(now - ONE_MINUTE)
    )

    /**
     * Operator: the agent drives the device. The follow-up is the honest half of
     * the story — Android does not let an app flip Bluetooth, so the agent opens
     * the screen and hands over, which is worth seeing before it happens for real.
     */
    private fun deviceAction(lastModified: Long): ChatSession {
        val exchanges = listOf(
            "Turn on Wi-Fi" to "Wi-Fi is on.",
            "Now turn Bluetooth off" to
                "I've opened the Bluetooth screen for you. Android doesn't let an app flip " +
                "that switch itself, so tap it there and it's done."
        )
        return sample(
            id = DEVICE_ACTION_ID,
            title = "Turn on Wi-Fi",
            agentMode = "OPERATOR",
            lastModified = lastModified,
            exchanges = exchanges,
            display = listOf(
                ui(0, MessageKind.USER, exchanges[0].first),
                ui(1, MessageKind.TOOL, "toggle_wifi: Wi-Fi turned on (was off, now on)."),
                ui(2, MessageKind.ASSISTANT, exchanges[0].second),
                ui(3, MessageKind.USER, exchanges[1].first),
                ui(4, MessageKind.TOOL, "open_setting: Opened Bluetooth."),
                ui(5, MessageKind.ASSISTANT, exchanges[1].second)
            )
        )
    }

    /** Monitor: nothing is changed, the agent only looks and answers. */
    private fun screenQa(lastModified: Long): ChatSession {
        val exchanges = listOf(
            "What's on my screen?" to
                "You're on a flight booking page — a Berlin → Lisbon search for 14 March, " +
                "with the cheapest fare at the top at €89 and a Continue button under it. " +
                "Ask me to compare the options or read out any part of it."
        )
        return sample(
            id = SCREEN_QA_ID,
            title = "What's on my screen?",
            agentMode = "MONITOR",
            lastModified = lastModified,
            exchanges = exchanges,
            display = listOf(
                ui(0, MessageKind.USER, exchanges[0].first),
                ui(1, MessageKind.TOOL, "read_screen: Read the screen (31 elements)."),
                ui(2, MessageKind.ASSISTANT, exchanges[0].second)
            )
        )
    }

    /**
     * The on-screen transcript ([display]) carries the tool bubbles, because
     * that is what a real run looks like. The LLM-shaped history carries only
     * the user/assistant text of [exchanges] — a fabricated `tool_calls` entry
     * with no matching id would make the provider reject the very first request
     * if the user carried the sample on, and the text alone is enough context
     * for them to do so.
     */
    private fun sample(
        id: String,
        title: String,
        agentMode: String,
        lastModified: Long,
        exchanges: List<Pair<String, String>>,
        display: List<UiMessage>
    ) = ChatSession(
        id = id,
        title = title,
        lastModified = lastModified,
        messages = exchanges.flatMap { (user, assistant) ->
            listOf(
                ChatMessage(role = "user", content = JsonPrimitive(user)),
                ChatMessage(role = "assistant", content = JsonPrimitive(assistant))
            )
        },
        displayMessages = display,
        agentMode = agentMode,
        isSample = true
    )

    private fun ui(id: Long, kind: MessageKind, text: String) = UiMessage(id, kind, text)

    private const val ONE_MINUTE = 60_000L
    private const val TWO_MINUTES = 120_000L
}
