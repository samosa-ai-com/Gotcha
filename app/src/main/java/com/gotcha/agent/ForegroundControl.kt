package com.gotcha.agent

import com.gotcha.tools.ToolCategories
import com.gotcha.tools.ToolResult
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject

/**
 * What the user is asked before Gotcha first takes control of another app in a
 * request (issue #98): which app, why, what Gotcha may do there, and what a
 * denial means.
 */
data class ForegroundControlRequest(
    val toolName: String,
    /** The app Gotcha wants to open or control, or null when it can't tell yet. */
    val appLabel: String?,
    /** What the user asked for; quoted as the reason. */
    val userRequest: String,
    /** navigate_app's task, when that is what asked: more specific than [userRequest]. */
    val task: String? = null
) {
    val title: String get() = "Let Gotcha control ${appLabel ?: "your apps"}?"

    fun promptText(): String {
        val target = appLabel ?: "another app"
        val why = task?.trim()?.takeIf { it.isNotEmpty() }
            ?.let { "Gotcha wants to: ${it.take(MAX_QUOTE)}" }
            ?: userRequest.trim().takeIf { it.isNotEmpty() }
                ?.let { "To do what you asked (“${it.take(MAX_QUOTE)}”), Gotcha needs to use $target." }
            ?: "To do what you asked, Gotcha needs to use $target."
        return why + "\n\n" +
            "Gotcha may bring $target to the front and tap, type, scroll and read its screen " +
            "until this request is done. You'll see when it has finished.\n\n" +
            "If you deny, Gotcha won't open or control other apps for this request."
    }

    companion object {
        const val ALLOW_LABEL = "Allow for this request"
        const val DENY_LABEL = "Deny"
        const val DONE_MESSAGE = "Gotcha is done. You can use your app again."

        private const val MAX_QUOTE = 160

        fun controllingMessage(appLabel: String?): String =
            "Gotcha is controlling ${appLabel ?: "your screen"}…"
    }
}

/**
 * One "may Gotcha control your apps?" answer per top-level request (issue #98).
 *
 * [check] runs before every tool call, including those a `task` or
 * `navigate_app` sub-agent makes. The first foreground-control tool in a request
 * asks through [ask]; the answer, yes or no, then holds for every later tap,
 * screen read or navigation step until [reset] ends the request. Background and
 * info tools pass straight through.
 */
class ForegroundControlGate(
    private val ask: suspend (toolName: String, args: JsonObject) -> Boolean,
    /** Called once per request, when the first allowed foreground-control tool runs. */
    private val onControlStarted: () -> Unit = {}
) {
    // Held across the ask, so calls racing the first one wait for its answer
    // instead of raising a second prompt.
    private val mutex = Mutex()

    @Volatile
    private var decision: Boolean? = null

    /** True once a foreground-control tool was allowed to run in this request. */
    @Volatile
    var inControl: Boolean = false
        private set

    /** Null to let [toolName] run; otherwise the result to return in its place. */
    suspend fun check(toolName: String, args: JsonObject): ToolResult? {
        if (!ToolCategories.isForegroundControl(toolName)) return null
        val allowed = mutex.withLock {
            decision ?: ask(toolName, args).also { decision = it }
        }
        if (!allowed) return ToolResult.error(DENIED_MESSAGE)
        if (!inControl) {
            inControl = true
            onControlStarted()
        }
        return null
    }

    /**
     * Ends the request: forgets the answer. Returns whether Gotcha had taken
     * control during it, so the host can say that control is over.
     */
    fun reset(): Boolean {
        val hadControl = inControl
        decision = null
        inControl = false
        return hadControl
    }

    companion object {
        const val DENIED_MESSAGE =
            "The user did not allow Gotcha to open or control other apps for this request. " +
                "Do not retry open_app, open_setting, navigate_app, screen reading or " +
                "accessibility actions. Answer without them, or tell the user what you could not do."
    }
}
