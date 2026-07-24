package com.gotcha.service

import android.graphics.Bitmap
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import kotlinx.coroutines.tasks.await

/**
 * On-device ML Kit visual QR code and barcode detector for screen snapshots.
 */
object QrCodeScanner {

    private val scanner by lazy {
        val options = BarcodeScannerOptions.Builder()
            .setBarcodeFormats(Barcode.FORMAT_ALL_FORMATS)
            .build()
        BarcodeScanning.getClient(options)
    }

    /**
     * Scans [bitmap] for QR codes and 1D/2D barcodes using ML Kit on-device recognition.
     */
    suspend fun scanBitmap(bitmap: Bitmap): List<DetectedEntity> {
        return try {
            val image = InputImage.fromBitmap(bitmap, 0)
            val barcodes = scanner.process(image).await()
            barcodes.mapNotNull { barcodeToEntity(it) }
        } catch (_: Throwable) {
            emptyList()
        }
    }

    fun barcodeToEntity(barcode: Barcode): DetectedEntity? {
        val rawValue = barcode.rawValue ?: barcode.displayValue ?: return null
        if (rawValue.isBlank()) return null

        val isQr = barcode.format == Barcode.FORMAT_QR_CODE
        val type = if (isQr) EntityType.QR_CODE else EntityType.BARCODE
        val actions = mutableListOf<SmartAction>()

        val displayTitle = buildQrActions(barcode, rawValue, isQr, actions)

        return DetectedEntity(
            type = type,
            rawValue = rawValue,
            normalizedValue = displayTitle,
            span = 0..rawValue.length,
            confidence = 0.98f,
            actions = actions
        )
    }

    @Suppress("LongMethod", "CyclomaticComplexMethod")
    private fun buildQrActions(
        barcode: Barcode,
        rawValue: String,
        isQr: Boolean,
        actions: MutableList<SmartAction>
    ): String {
        val clean = rawValue.trim()

        // 1. ML Kit recognized URL
        if (barcode.valueType == Barcode.TYPE_URL) {
            val url = barcode.url?.url ?: clean
            val domain = runCatching { android.net.Uri.parse(url).host }.getOrNull()
                ?.ifBlank { null } ?: SmartActionDetector.snippet(url, 24)
            actions.add(
                SmartAction(
                    label = "🌐 Open QR Link: $domain",
                    prompt = SmartActionDetector.encode(SmartActionDetector.TYPE_VIEW, url),
                    actionType = ActionType.NATIVE_BROWSE,
                    isPrimary = true
                )
            )
            actions.add(
                SmartAction(
                    label = "📝 Summarize",
                    prompt = SmartActionDetector.encode(SmartActionDetector.TYPE_FETCH, url),
                    actionType = ActionType.LLM_SUMMARIZE
                )
            )
            actions.add(
                SmartAction(
                    label = "📋 Copy URL",
                    prompt = SmartActionDetector.encode(SmartActionDetector.TYPE_COPY, url),
                    actionType = ActionType.NATIVE_COPY
                )
            )
            return "QR Link: $domain"
        }

        // 2. ML Kit recognized Wi-Fi
        if (barcode.valueType == Barcode.TYPE_WIFI) {
            val ssid = barcode.wifi?.ssid ?: "Wi-Fi"
            val pwd = barcode.wifi?.password ?: ""
            val info = "SSID: $ssid, Password: $pwd"
            actions.add(
                SmartAction(
                    label = "📶 Wi-Fi: $ssid",
                    prompt = SmartActionDetector.encode(SmartActionDetector.TYPE_COPY, info),
                    actionType = ActionType.NATIVE_COPY,
                    isPrimary = true
                )
            )
            return "Wi-Fi QR: $ssid"
        }

        // 3. ML Kit recognized Phone
        if (barcode.valueType == Barcode.TYPE_PHONE) {
            val phone = barcode.phone?.number ?: clean
            actions.add(
                SmartAction(
                    label = "📞 Call: ${SmartActionDetector.snippet(phone, 16)}",
                    prompt = SmartActionDetector.encode(SmartActionDetector.TYPE_DIAL, phone),
                    actionType = ActionType.NATIVE_DIAL,
                    isPrimary = true
                )
            )
            return "QR Phone: $phone"
        }

        // 4. UPI Payment scheme (upi://pay?pa=...)
        if (clean.startsWith("upi://", ignoreCase = true)) {
            val paMatch = Regex("pa=([^&]+)", RegexOption.IGNORE_CASE).find(clean)
            val payee = paMatch?.groupValues?.getOrNull(1)
                ?.let { android.net.Uri.decode(it) } ?: SmartActionDetector.snippet(clean, 20)
            actions.add(
                SmartAction(
                    label = "💳 Pay via UPI: $payee",
                    prompt = SmartActionDetector.encode(SmartActionDetector.TYPE_VIEW, clean),
                    actionType = ActionType.NATIVE_BROWSE,
                    isPrimary = true
                )
            )
            actions.add(
                SmartAction(
                    label = "📋 Copy UPI Link",
                    prompt = SmartActionDetector.encode(SmartActionDetector.TYPE_COPY, clean),
                    actionType = ActionType.NATIVE_COPY
                )
            )
            return "UPI Payment: $payee"
        }

        // 5. Generic URI scheme (e.g. intent://, paytm://, phonepe://, geo:, mailto:, smsto:, market://)
        val hasScheme = Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://").containsMatchIn(clean) ||
            Regex("^(geo|mailto|tel|smsto|intent|market):", RegexOption.IGNORE_CASE).containsMatchIn(clean)

        if (hasScheme) {
            val schemeName = clean.substringBefore("://").substringBefore(":").lowercase()
            val displayDomain = runCatching { android.net.Uri.parse(clean).host }.getOrNull()
                ?.ifBlank { null } ?: schemeName
            actions.add(
                SmartAction(
                    label = "🌐 Open QR Link: $displayDomain",
                    prompt = SmartActionDetector.encode(SmartActionDetector.TYPE_VIEW, clean),
                    actionType = ActionType.NATIVE_BROWSE,
                    isPrimary = true
                )
            )
            actions.add(
                SmartAction(
                    label = "📋 Copy Link",
                    prompt = SmartActionDetector.encode(SmartActionDetector.TYPE_COPY, clean),
                    actionType = ActionType.NATIVE_COPY
                )
            )
            return "QR Link: $displayDomain"
        }

        // 6. Web URL without scheme (e.g. www.example.com/page, domain.com, sub.domain.org/path)
        val domainPattern = Regex(
            "^(www\\.|[a-zA-Z0-9-]+\\.(com|org|net|io|dev|app|co|in|uk|ca|au|de|fr|ai|me|info|gov|edu))(/[\\S]*)?$",
            RegexOption.IGNORE_CASE
        )
        if (domainPattern.matches(clean) || clean.startsWith("www.", ignoreCase = true)) {
            val fullUrl = if (clean.startsWith("http://", ignoreCase = true) ||
                clean.startsWith("https://", ignoreCase = true)
            ) {
                clean
            } else {
                "https://$clean"
            }
            val domain = runCatching { android.net.Uri.parse(fullUrl).host }.getOrNull()
                ?: SmartActionDetector.snippet(clean, 20)
            actions.add(
                SmartAction(
                    label = "🌐 Open QR Link: $domain",
                    prompt = SmartActionDetector.encode(SmartActionDetector.TYPE_VIEW, fullUrl),
                    actionType = ActionType.NATIVE_BROWSE,
                    isPrimary = true
                )
            )
            actions.add(
                SmartAction(
                    label = "📝 Summarize",
                    prompt = SmartActionDetector.encode(SmartActionDetector.TYPE_FETCH, fullUrl),
                    actionType = ActionType.LLM_SUMMARIZE
                )
            )
            actions.add(
                SmartAction(
                    label = "📋 Copy Link",
                    prompt = SmartActionDetector.encode(SmartActionDetector.TYPE_COPY, fullUrl),
                    actionType = ActionType.NATIVE_COPY
                )
            )
            return "QR Link: $domain"
        }

        // 7. If 1D Barcode
        if (!isQr) {
            val searchUrl = "https://www.google.com/search?q=${android.net.Uri.encode(clean)}"
            actions.add(
                SmartAction(
                    label = "🔍 Search Barcode: ${SmartActionDetector.snippet(clean, 16)}",
                    prompt = SmartActionDetector.encode(SmartActionDetector.TYPE_VIEW, searchUrl),
                    actionType = ActionType.NATIVE_BROWSE,
                    isPrimary = true
                )
            )
            actions.add(
                SmartAction(
                    label = "📋 Copy Barcode",
                    prompt = SmartActionDetector.encode(SmartActionDetector.TYPE_COPY, clean),
                    actionType = ActionType.NATIVE_COPY
                )
            )
            return "Barcode: ${SmartActionDetector.snippet(clean, 20)}"
        }

        // 8. General QR content fallback:
        // ALWAYS provide "🌐 Open QR Link" via TYPE_VIEW as PRIMARY action!
        val searchUrl = if (clean.startsWith("http://") || clean.startsWith("https://")) {
            clean
        } else {
            "https://www.google.com/search?q=${android.net.Uri.encode(clean)}"
        }
        actions.add(
            SmartAction(
                label = "🌐 Open QR Link: ${SmartActionDetector.snippet(clean, 18)}",
                prompt = SmartActionDetector.encode(SmartActionDetector.TYPE_VIEW, searchUrl),
                actionType = ActionType.NATIVE_BROWSE,
                isPrimary = true
            )
        )
        actions.add(
            SmartAction(
                label = "🤖 Ask Assistant",
                prompt = "Here is the content of a QR code detected on screen:\n\n$clean\n\n" +
                    "Please process or act on this QR code content.",
                actionType = ActionType.LLM_GENERAL
            )
        )
        actions.add(
            SmartAction(
                label = "📋 Copy QR Content",
                prompt = SmartActionDetector.encode(SmartActionDetector.TYPE_COPY, clean),
                actionType = ActionType.NATIVE_COPY
            )
        )
        return "QR: ${SmartActionDetector.snippet(clean, 20)}"
    }
}
