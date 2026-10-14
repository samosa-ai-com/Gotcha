package com.gotcha.service

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Real-time currency exchange rates service powered by fawazahmed0/exchange-api.
 * Provides instant currency conversion without sending LLM prompts.
 */
object CurrencyExchangeService {

    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()

    fun extractCurrencyCode(price: String): String =
        SmartActionDetector.extractCurrencyCode(price).ifBlank { "USD" }

    /**
     * The amount in [price], whatever separators it uses.
     *
     * A final comma followed by one or two digits is the decimal mark
     * ("1 234,56 ₽" → 1234.56, "1.234,56 €" → 1234.56); any other comma, and
     * any space, groups thousands ("₹1,23,456.89" → 123456.89, "$1,234" → 1234).
     */
    fun parseAmount(price: String): Double? {
        // trim: "Rs. 500" leaves a leading dot that would read as 0.5.
        val clean = price.replace(Regex("[^0-9.,]"), "").trim('.', ',')
        val decimalComma = Regex(",\\d{1,2}$").containsMatchIn(clean)
        val normalized = if (decimalComma) {
            clean.replace(".", "").replace(',', '.')
        } else {
            clean.replace(",", "")
        }
        return normalized.toDoubleOrNull()
    }

    fun convert(price: String, targetCurrency: String): String? {
        val amount = parseAmount(price) ?: return null
        val fromCode = extractCurrencyCode(price).lowercase()
        val toCode = targetCurrency.lowercase().take(3)

        if (fromCode == toCode) {
            return "$price is already in ${toCode.uppercase()}"
        }

        val primaryUrl = "https://cdn.jsdelivr.net/npm/@fawazahmed0/currency-api@latest/v1/currencies/$fromCode.json"
        val fallbackUrl = "https://latest.currency-api.pages.dev/v1/currencies/$fromCode.json"

        val jsonStr = fetchUrl(primaryUrl) ?: fetchUrl(fallbackUrl) ?: return null
        return try {
            val json = JSONObject(jsonStr)
            val ratesObj = json.getJSONObject(fromCode)
            val rate = ratesObj.getDouble(toCode)
            val converted = amount * rate
            val dateStr = json.optString("date", "")
            val dateSuffix = if (dateStr.isNotBlank()) " (as of $dateStr)" else ""

            String.format(
                Locale.US,
                "💵 %s = %.2f %s\n(1 %s = %.4f %s%s)",
                price,
                converted,
                toCode.uppercase(),
                fromCode.uppercase(),
                rate,
                toCode.uppercase(),
                dateSuffix
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun fetchUrl(url: String): String? {
        val request = Request.Builder().url(url).build()
        return try {
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) response.body?.string() else null
            }
        } catch (_: Exception) {
            null
        }
    }
}
