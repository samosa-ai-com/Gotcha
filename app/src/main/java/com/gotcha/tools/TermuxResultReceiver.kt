package com.gotcha.tools

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Receives the result of a Termux RUN_COMMAND, sent back through the [android.app.PendingIntent]
 * that [TermuxTool] attached to the request.
 *
 * Not exported, and it does not need to be: a PendingIntent is fired by the system with the
 * *creator's* identity, so Termux triggering it reaches this receiver without Termux ever having
 * permission to broadcast to us directly.
 */
class TermuxResultReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val requestCode = intent.getIntExtra(EXTRA_REQUEST_CODE, -1)
        val bundle = intent.getBundleExtra(TermuxTool.RESULT_BUNDLE) ?: return
        // An unknown code means the caller already gave up (timed out) — nothing to resume.
        if (requestCode >= 0) TermuxTool.completeResult(requestCode, bundle)
    }

    companion object {
        private const val EXTRA_REQUEST_CODE = "com.gotcha.termux.REQUEST_CODE"

        /**
         * The intent Termux will send back. Explicit, so the PendingIntent can safely be mutable
         * — Termux only gets to add its result bundle, not to redirect the delivery.
         */
        internal fun resultIntent(context: Context, requestCode: Int): Intent =
            Intent(context, TermuxResultReceiver::class.java)
                .putExtra(EXTRA_REQUEST_CODE, requestCode)
    }
}
