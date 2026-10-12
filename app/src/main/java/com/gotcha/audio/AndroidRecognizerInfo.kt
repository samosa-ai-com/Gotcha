package com.gotcha.audio

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.speech.RecognitionService
import android.speech.RecognitionSupport
import android.speech.RecognitionSupportCallback
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.annotation.RequiresApi
import com.gotcha.i18n.Language
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * What Android's built-in speech recognition will use, for the Speech and
 * Language pages (#122). [SpeechRecognizer.createSpeechRecognizer] without a
 * component binds the phone's default recognizer, so that is the one described.
 */
object AndroidRecognizerInfo {

    /** Secure setting naming the default recognizer; not public API but readable. */
    private const val DEFAULT_RECOGNIZER_SETTING = "voice_recognition_service"
    private const val SUPPORT_TIMEOUT_MS = 3000L

    /**
     * The default recognizer's name, or null when Android names none and more
     * than one, or none, is installed.
     */
    fun name(context: Context): String? {
        val pm = context.packageManager
        val component = defaultComponent(context)
        val packageName = component?.packageName
            ?: pm.queryIntentServices(Intent(RecognitionService.SERVICE_INTERFACE), 0)
                .singleOrNull()?.serviceInfo?.packageName
            ?: return null
        return try {
            pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
        } catch (_: Exception) {
            packageName
        }
    }

    private fun defaultComponent(context: Context): ComponentName? =
        try {
            Settings.Secure.getString(context.contentResolver, DEFAULT_RECOGNIZER_SETTING)
                ?.takeIf { it.isNotBlank() }
                ?.let(ComponentName::unflattenFromString)
        } catch (_: Exception) {
            null
        }

    /**
     * Whether the default recognizer can transcribe [language], or null when it
     * can't say: Android before 13 has no way to ask, and many recognizers
     * don't answer or answer with empty lists.
     */
    suspend fun supports(context: Context, language: Language): Boolean? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return null
        if (!SpeechRecognizer.isRecognitionAvailable(context)) return null
        return withContext(Dispatchers.Main) {
            // SpeechRecognizer must be created and driven from the main thread.
            val recognizer = try {
                SpeechRecognizer.createSpeechRecognizer(context)
            } catch (_: Exception) {
                return@withContext null
            }
            try {
                withTimeoutOrNull(SUPPORT_TIMEOUT_MS) { checkSupport(context, recognizer, language) }
            } finally {
                recognizer.destroy()
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private suspend fun checkSupport(context: Context, recognizer: SpeechRecognizer, language: Language): Boolean? =
        suspendCancellableCoroutine { cont ->
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            try {
                recognizer.checkRecognitionSupport(
                    intent,
                    context.mainExecutor,
                    object : RecognitionSupportCallback {
                        override fun onSupportResult(support: RecognitionSupport) {
                            if (cont.isActive) cont.resume(supportsLanguage(support, language))
                        }

                        override fun onError(error: Int) {
                            if (cont.isActive) cont.resume(null)
                        }
                    }
                )
            } catch (_: Exception) {
                if (cont.isActive) cont.resume(null)
            }
        }

    /** Null when [support] lists no languages at all, since that says nothing. */
    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    internal fun supportsLanguage(support: RecognitionSupport, language: Language): Boolean? {
        val listed = support.installedOnDeviceLanguages + support.pendingOnDeviceLanguages +
            support.supportedOnDeviceLanguages + support.onlineLanguages
        if (listed.isEmpty()) return null
        return listed.any(language::matchesCode)
    }
}
