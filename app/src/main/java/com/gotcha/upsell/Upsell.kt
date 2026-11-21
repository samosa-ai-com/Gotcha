package com.gotcha.upsell

import android.content.Context
import android.content.SharedPreferences
import com.gotcha.data.LlmProvider
import com.gotcha.data.Settings
import com.gotcha.tools.ShellEntitlement

/** Who a nudge talks to. */
enum class UpsellAudience {
    /** No Samosa AI account on this phone: the nudge introduces Samosa AI itself. */
    DISCOVER_SAMOSA,

    /** A Samosa AI account on a plan without Pro's features: the nudge offers Pro. */
    PRO,

    /** Already on Pro, or the plan is not known yet: no nudge. */
    NONE
}

/** What brought a nudge up. It picks the opening line; the list of features is the same. */
enum class UpsellReason {
    /** Something needed the Linux shell, which is part of Pro. */
    SHELL,

    /** The chat model said no more for now: out of credits, or a rate limit. */
    LIMITS,

    /** The user has had a good run of tasks; the occasional "did you know". */
    VALUE,

    /** Opened by the user from a menu or a settings row. */
    GENERAL
}

/**
 * Who sees which nudge, and when the occasional one may interrupt. Pure functions, so the rules
 * are tested without a screen; [UpsellStore] holds what they need to remember.
 */
object Upsell {

    private const val DAY_MS = 24L * 60L * 60L * 1000L

    /** No interrupting nudge before the user has had this long with Gotcha. */
    const val FIRST_AFTER_MS = 3L * DAY_MS

    /** The gap between two interrupting nudges. */
    const val COOLDOWN_MS = 7L * DAY_MS

    /** Kept apart from the "Enjoying Gotcha?" ask, so the two never come one after the other. */
    const val AFTER_STAR_PROMPT_MS = 2L * DAY_MS

    /** Successful tasks before the first interrupting nudge: Gotcha has shown what it does. */
    const val MIN_SUCCESSFUL_RUNS = 5

    /** How long after leaving for the account website a return still checks for a new plan. */
    const val UPGRADE_CHECK_WINDOW_MS = 60L * 60L * 1000L

    /** "Not now" this many times and the interrupting nudge stops for that audience. */
    const val MAX_DISMISSALS = 3

    fun audience(signedIn: Boolean, tierId: String?): UpsellAudience = when {
        !signedIn -> UpsellAudience.DISCOVER_SAMOSA
        tierId == null -> UpsellAudience.NONE
        tierId.lowercase() in ShellEntitlement.SHELL_TIERS -> UpsellAudience.NONE
        else -> UpsellAudience.PRO
    }

    fun audience(context: Context, settings: Settings): UpsellAudience =
        audience(settings.samosaSessionToken.isNotBlank(), ShellEntitlement.knownTier(context))

    /**
     * Whether the model is given [com.gotcha.tools.ShellTools.OFFER]: no Linux shell can run here,
     * and the account is one Pro would give a shell to. A Pro account whose shell is just not
     * installed yet is told so by the shell's own status instead.
     */
    fun offersLinuxShell(context: Context, settings: Settings): Boolean =
        audience(context, settings) != UpsellAudience.NONE &&
            !com.gotcha.tools.ShellBackends.active(context).status().usable

    /**
     * The audience for a "you have hit a limit" nudge: only where the answer would lift the limit.
     * Pro lifts Samosa AI's limits, not another provider's; Samosa AI is offered to someone whose
     * own key ran out, but not to a signed-in user who chose another provider on purpose.
     */
    fun limitsAudience(audience: UpsellAudience, provider: LlmProvider): UpsellAudience = when {
        audience == UpsellAudience.PRO && provider == LlmProvider.SAMOSA_AI -> UpsellAudience.PRO
        audience == UpsellAudience.DISCOVER_SAMOSA && provider == LlmProvider.OPENAI_COMPATIBLE ->
            UpsellAudience.DISCOVER_SAMOSA
        else -> UpsellAudience.NONE
    }

    /**
     * When the interrupting nudge (a dialog on Home) is next due, or null when it should not
     * come. Nudges the user opens themselves, and the ones shown where a feature is missing, are
     * not paced: they answer something the user just did.
     */
    fun dialogDueAt(
        audience: UpsellAudience,
        installedAt: Long,
        lastShownAt: Long,
        dismissals: Int,
        successfulRuns: Int,
        starPromptShownAt: Long
    ): Long? = when {
        audience == UpsellAudience.NONE -> null
        dismissals >= MAX_DISMISSALS -> null
        successfulRuns < MIN_SUCCESSFUL_RUNS -> null
        else -> maxOf(
            installedAt + FIRST_AFTER_MS,
            lastShownAt + COOLDOWN_MS,
            starPromptShownAt + AFTER_STAR_PROMPT_MS
        )
    }
}

/** What the nudges remember, in Gotcha's settings preferences. */
class UpsellStore(private val prefs: SharedPreferences) {

    fun lastShownAt(): Long = prefs.getLong(KEY_LAST_SHOWN, 0L)

    /** Counted per audience: a user who waved off Samosa AI may still hear about Pro once signed in. */
    fun dismissals(audience: UpsellAudience): Int = prefs.getInt(dismissalsKey(audience), 0)

    fun successfulRuns(): Int = prefs.getInt(KEY_RUNS, 0)

    /** Recorded as the nudge appears, so one the process died under still waits out its cooldown. */
    fun markShown(at: Long) {
        prefs.edit().putLong(KEY_LAST_SHOWN, at).apply()
    }

    fun markDismissed(audience: UpsellAudience) {
        prefs.edit().putInt(dismissalsKey(audience), dismissals(audience) + 1).apply()
    }

    /** Counts up to what the rules look at, then stops writing. */
    fun recordSuccessfulRun() {
        val runs = successfulRuns()
        if (runs < Upsell.MIN_SUCCESSFUL_RUNS) prefs.edit().putInt(KEY_RUNS, runs + 1).apply()
    }

    /**
     * When the user last left for the account website to get Pro, or zero. While that is
     * recent, each return to Gotcha checks the plan at once, so a new Pro plan is welcomed.
     */
    fun upgradeStartedAt(): Long = prefs.getLong(KEY_UPGRADE_STARTED, 0L)

    fun setUpgradeStartedAt(at: Long) {
        prefs.edit().putLong(KEY_UPGRADE_STARTED, at).apply()
    }

    private fun dismissalsKey(audience: UpsellAudience) = "$KEY_DISMISSALS_PREFIX${audience.name.lowercase()}"

    companion object {
        const val KEY_LAST_SHOWN = "upsell_last_shown_at"
        const val KEY_DISMISSALS_PREFIX = "upsell_dismissals_"
        const val KEY_RUNS = "upsell_successful_runs"
        const val KEY_UPGRADE_STARTED = "upsell_upgrade_started_at"
    }
}
