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

    @Suppress("LongMethod", "CyclomaticComplexMethod")
    fun barcodeToEntity(barcode: Barcode): DetectedEntity? {
        val rawValue = barcode.rawValue ?: return null
        if (rawValue.isBlank()) return null

        val isQr = barcode.format == Barcode.FORMAT_QR_CODE
        val type = if (isQr) EntityType.QR_CODE else EntityType.BARCODE
        val actions = mutableListOf<SmartAction>()

        val displayTitle = when (barcode.valueType) {
            Barcode.TYPE_URL -> {
                val url = barcode.url?.url ?: rawValue
                val domain = runCatching { android.net.Uri.parse(url).host }.getOrNull()
                    ?: SmartActionDetector.snippet(url, 20)
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
                "QR Link: $domain"
            }
            Barcode.TYPE_WIFI -> {
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
                "Wi-Fi QR: $ssid"
            }
            Barcode.TYPE_PHONE -> {
                val phone = barcode.phone?.number ?: rawValue
                actions.add(
                    SmartAction(
                        label = "📞 Call: ${SmartActionDetector.snippet(phone, 15)}",
                        prompt = SmartActionDetector.encode(SmartActionDetector.TYPE_DIAL, phone),
                        actionType = ActionType.NATIVE_DIAL,
                        isPrimary = true
                    )
                )
                "QR Phone: $phone"
            }
            else -> {
                if (rawValue.startsWith("http://", ignoreCase = true) ||
                    rawValue.startsWith("https://", ignoreCase = true)
                ) {
                    val domain = runCatching { android.net.Uri.parse(rawValue).host }.getOrNull()
                        ?: SmartActionDetector.snippet(rawValue, 20)
                    actions.add(
                        SmartAction(
                            label = "🌐 Open QR Link: $domain",
                            prompt = SmartActionDetector.encode(SmartActionDetector.TYPE_VIEW, rawValue),
                            actionType = ActionType.NATIVE_BROWSE,
                            isPrimary = true
                        )
                    )
                    actions.add(
                        SmartAction(
                            label = "📝 Summarize",
                            prompt = SmartActionDetector.encode(SmartActionDetector.TYPE_FETCH, rawValue),
                            actionType = ActionType.LLM_SUMMARIZE
                        )
                    )
                    actions.add(
                        SmartAction(
                            label = "📋 Copy Link",
                            prompt = SmartActionDetector.encode(SmartActionDetector.TYPE_COPY, rawValue),
                            actionType = ActionType.NATIVE_COPY
                        )
                    )
                    "QR Link: $domain"
                } else if (!isQr) {
                    val searchUrl = "https://www.google.com/search?q=${android.net.Uri.encode(rawValue)}"
                    actions.add(
                        SmartAction(
                            label = "🔍 Search Barcode: ${SmartActionDetector.snippet(rawValue, 16)}",
                            prompt = SmartActionDetector.encode(SmartActionDetector.TYPE_VIEW, searchUrl),
                            actionType = ActionType.NATIVE_BROWSE,
                            isPrimary = true
                        )
                    )
                    actions.add(
                        SmartAction(
                            label = "📋 Copy Barcode",
                            prompt = SmartActionDetector.encode(SmartActionDetector.TYPE_COPY, rawValue),
                            actionType = ActionType.NATIVE_COPY
                        )
                    )
                    "Barcode: ${SmartActionDetector.snippet(rawValue, 20)}"
                } else {
                    actions.add(
                        SmartAction(
                            label = "📷 QR: ${SmartActionDetector.snippet(rawValue, 20)}",
                            prompt = "Here is the content of a QR code detected on screen:\n\n$rawValue\n\n" +
                                "Please process or act on this QR code content.",
                            actionType = ActionType.LLM_GENERAL,
                            isPrimary = true
                        )
                    )
                    actions.add(
                        SmartAction(
                            label = "📋 Copy QR Content",
                            prompt = SmartActionDetector.encode(SmartActionDetector.TYPE_COPY, rawValue),
                            actionType = ActionType.NATIVE_COPY
                        )
                    )
                    "QR: ${SmartActionDetector.snippet(rawValue, 20)}"
                }
            }
        }

        return DetectedEntity(
            type = type,
            rawValue = rawValue,
            normalizedValue = displayTitle,
            span = 0..rawValue.length,
            confidence = 0.98f,
            actions = actions
        )
    }
}
