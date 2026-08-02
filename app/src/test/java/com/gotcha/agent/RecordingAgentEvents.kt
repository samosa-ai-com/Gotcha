package com.gotcha.agent

import com.gotcha.data.RunSummary

/**
 * An [AgentEvents] that records what the agent loop emitted instead of driving a UI,
 * so tests can assert on the loop's externally visible behaviour.
 */
class RecordingAgentEvents : AgentEvents {

    val uiMessages = mutableListOf<String>()
    val activities = mutableListOf<String?>()
    val assistantReplies = mutableListOf<String>()
    val permissionRequests = mutableListOf<String>()
    val confirmationRequests = mutableListOf<String>()
    val runSummaries = mutableListOf<RunSummary>()
    val screenCaptureChrome = mutableListOf<Boolean>()
    var screenReadDoneCount = 0
        private set
    var historyResets = 0
        private set

    /** What [awaitConfirmation] should answer. Defaults to approving. */
    var confirmationAnswer: Boolean = true

    /** What [awaitQuestionAnswer] should answer. */
    var questionAnswer: String = ""

    override fun onUi(
        kind: MessageKind,
        text: String,
        imageBase64: String?,
        subAgentSteps: List<String>,
        reasoningContent: String?
    ) {
        uiMessages += text
    }

    override fun onActivity(activity: String?) {
        activities += activity
    }

    override fun onTokenCount(totalTokens: Int) = Unit

    override fun onAssistantReply(text: String) {
        assistantReplies += text
    }

    override fun onSubAgentUpdate(running: String?, currentAction: String?) = Unit

    override fun onPermissionRequest(marker: String) {
        permissionRequests += marker
    }

    override fun onHistoryReset() {
        historyResets++
    }

    override fun onRunSummary(runSummary: RunSummary) {
        runSummaries += runSummary
    }

    override fun onScreenCaptureChrome(hide: Boolean) {
        screenCaptureChrome += hide
    }

    override fun onScreenReadDone() {
        screenReadDoneCount++
    }

    override suspend fun awaitQuestionAnswer(question: PendingQuestion): String = questionAnswer

    override suspend fun awaitConfirmation(toolNames: List<String>, description: String): Boolean {
        confirmationRequests += "${toolNames.joinToString(",")}: $description"
        return confirmationAnswer
    }
}
