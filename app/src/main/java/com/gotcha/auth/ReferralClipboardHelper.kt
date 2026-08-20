package com.gotcha.auth

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import com.gotcha.util.GotchaLog

/**
 * Helper for referral code clipboard detection, copying, and sharing.
 */
object ReferralClipboardHelper {

    private const val TAG = "ReferralClipboard"
    private val REFERRAL_REGEX = Regex("AIR-[A-Z0-9]{5,8}")

    /**
     * Scans the system clipboard for an invite code matching AIR-[A-Z0-9]{5,8}.
     * Returns the uppercase normalized code, or null if none found.
     */
    fun getReferralFromClipboard(context: Context): String? {
        return try {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                ?: return null
            val item = clipboard.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)
            val text = item?.text?.toString() ?: return null
            REFERRAL_REGEX.find(text.uppercase().trim())?.value
        } catch (e: Exception) {
            GotchaLog.d(TAG, e) { "Failed to read clipboard for referral code" }
            null
        }
    }

    /**
     * Copies the referral code to the system clipboard and displays a short confirmation toast.
     */
    fun copyReferralCode(context: Context, code: String) {
        try {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            val clip = ClipData.newPlainText("Gotcha Referral Code", code)
            clipboard?.setPrimaryClip(clip)
            Toast.makeText(context, "Copied invite code: $code", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            GotchaLog.d(TAG, e) { "Failed to copy referral code to clipboard" }
        }
    }

    /**
     * Opens the Android system Share sheet with the referral link and code.
     */
    fun shareReferralLink(context: Context, code: String, shareUrl: String) {
        try {
            val url = shareUrl.ifBlank { "https://api.samosa-ai.com/join?ref=$code" }
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(
                    Intent.EXTRA_TEXT,
                    "Try Gotcha — use my invite code $code to get bonus credits: $url"
                )
                putExtra(Intent.EXTRA_SUBJECT, "Invite to Gotcha")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            val chooser = Intent.createChooser(shareIntent, "Share invite").apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(chooser)
        } catch (e: Exception) {
            GotchaLog.d(TAG, e) { "Failed to launch share sheet" }
        }
    }
}
