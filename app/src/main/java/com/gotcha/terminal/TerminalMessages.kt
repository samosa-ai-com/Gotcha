package com.gotcha.terminal

import android.content.Context
import com.gotcha.R
import com.gotcha.tools.CompanionTerminal.Refusal
import com.gotcha.tools.CompanionUnavailable
import com.gotcha.tools.ShellEntitlement

/** The sentence the user reads for each way the terminal can refuse to start. */
fun refusalMessage(context: Context, refusal: Refusal): String = when (refusal) {
    is Refusal.NotAllowed -> context.getString(
        when (refusal.access) {
            ShellEntitlement.Access.SIGNED_OUT -> R.string.linux_terminal_refused_signed_out
            ShellEntitlement.Access.NEEDS_CHECK -> R.string.linux_terminal_refused_needs_check
            else -> R.string.linux_terminal_refused_not_pro
        }
    )
    is Refusal.Unavailable -> context.getString(
        when (refusal.reason) {
            CompanionUnavailable.Reason.NOT_INSTALLED -> R.string.linux_terminal_refused_not_installed
            CompanionUnavailable.Reason.UNTRUSTED -> R.string.linux_terminal_refused_untrusted
            CompanionUnavailable.Reason.BIND_REFUSED,
            CompanionUnavailable.Reason.BIND_TIMED_OUT -> R.string.linux_terminal_refused_no_answer
        }
    )
    is Refusal.TooOld -> context.getString(R.string.linux_terminal_refused_too_old, refusal.found, refusal.needed)
    is Refusal.NoLinuxSystem -> context.getString(
        if (refusal.installing) R.string.linux_terminal_refused_installing else R.string.linux_terminal_refused_no_system
    )
    is Refusal.TooManySessions ->
        if (refusal.limit > 0) {
            context.getString(R.string.linux_terminal_refused_too_many, refusal.limit)
        } else {
            context.getString(R.string.linux_terminal_refused_too_many_unknown)
        }
    is Refusal.Failed ->
        refusal.detail?.takeIf { it.isNotBlank() }
            ?.let { context.getString(R.string.linux_terminal_refused_failed, it) }
            ?: context.getString(R.string.linux_terminal_refused_failed_unknown)
}
