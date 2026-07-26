package com.gotcha.agent.skills

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Fetches a community skill JSON from a URL, validates it, and returns a
 * preview that the user can confirm before persisting.
 *
 * The fetcher is intentionally tiny: HTTPS only, host allowlist, short
 * timeouts, a single body-size cap, and a strict JSON parser. We follow
 * redirects only when each hop lands on a host in the same allowlist
 * (e.g. samosa-ai.example -> samosa.ai), and never chase cross-host redirects.
 */
@Suppress("ThrowsCount", "SwallowedException")
class SkillImporter(
    private val allowedHosts: Set<String>,
    private val client: OkHttpClient = defaultClient(),
    /** Test-only override: when false, HTTP URLs are accepted. Default true. */
    private val requireHttps: Boolean = true,
    /** Maximum number of same-allowlist redirects to follow. */
    private val maxRedirects: Int = 4
) {

    fun fetchPreview(url: String): SkillPreview {
        var currentUrl = validateUrl(url)
        var hops = 0
        while (true) {
            val req = Request.Builder().url(currentUrl).get().build()
            val response = try {
                client.newCall(req).execute()
            } catch (e: IOException) {
                throw SkillImportException(
                    "Network error talking to ${currentUrl.host}: ${describe(e)}",
                    e
                )
            }
            val handled: SkillPreview? = response.use { resp -> handleResponse(resp, currentUrl) }
            if (handled != null) return handled
            // Otherwise it was a redirect — figure out the next URL.
            val location = response.header("Location")
                ?: throw SkillImportException("Redirect without Location header from $currentUrl")
            if (hops >= maxRedirects) {
                throw SkillImportException(
                    "Too many redirects (>$maxRedirects) from $currentUrl"
                )
            }
            val next = currentUrl.resolve(location)
                ?: throw SkillImportException("Bad redirect location: $location")
            if (requireHttps && !next.isHttps) {
                throw SkillImportException("Redirect target '$next' is not HTTPS")
            }
            if (allowedHosts.none { it.equals(next.host, ignoreCase = true) }) {
                throw SkillImportException(
                    "Redirect target host '${next.host}' is not in the allowlist"
                )
            }
            currentUrl = next
            hops++
        }
    }

    private fun handleResponse(
        resp: okhttp3.Response,
        currentUrl: okhttp3.HttpUrl
    ): SkillPreview? {
        val code = resp.code
        if (code in 300..399 && resp.header("Location") != null) {
            return null // signal "follow the redirect"
        }
        if (!resp.isSuccessful) {
            throw SkillImportException("HTTP $code from $currentUrl")
        }
        val body = resp.body?.string()
            ?: throw SkillImportException("Empty response body")
        if (body.length > MAX_BODY_BYTES) {
            throw SkillImportException("Response body exceeds $MAX_BODY_BYTES bytes")
        }
        val skill = parseAndValidate(body)
        return SkillPreview(
            skill = skill,
            sourceUrl = currentUrl.toString(),
            rawJson = body
        )
    }

    fun previewFromRawJson(rawJson: String, sourceLabel: String): SkillPreview {
        val skill = parseAndValidate(rawJson)
        return SkillPreview(skill = skill, sourceUrl = sourceLabel, rawJson = rawJson)
    }

    private fun validateUrl(url: String): okhttp3.HttpUrl {
        val parsed = url.toHttpUrlOrNull()
            ?: throw SkillImportException("Invalid URL")
        if (requireHttps && !parsed.isHttps) {
            throw SkillImportException("Only HTTPS URLs are allowed")
        }
        if (allowedHosts.isEmpty()) {
            throw SkillImportException("No community skill hosts are configured")
        }
        if (allowedHosts.none { it.equals(parsed.host, ignoreCase = true) }) {
            throw SkillImportException(
                "Host '${parsed.host}' is not in the community skill allowlist"
            )
        }
        return parsed
    }

    private fun describe(t: Throwable): String {
        val msg = t.message
        if (!msg.isNullOrBlank()) return msg
        return t::class.java.simpleName ?: t::class.java.name
    }

    companion object {
        const val MAX_BODY_BYTES = 200 * 1024

        private val rawJson = Json { ignoreUnknownKeys = true }

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .callTimeout(20, TimeUnit.SECONDS)
            .followRedirects(false)
            .followSslRedirects(false)
            .build()

        /**
         * Parse a raw skill JSON string, run all validation, and return the
         * [Skill] without any network call. Used by the paste-JSON import
         * path and by tests.
         */
        fun parseAndValidate(raw: String): Skill {
            if (raw.length > MAX_BODY_BYTES) {
                throw SkillImportException("JSON exceeds $MAX_BODY_BYTES bytes")
            }
            val skill = try {
                rawJson.decodeFromString<Skill>(raw)
            } catch (e: Exception) {
                throw SkillImportException("Invalid JSON: ${e.message ?: e::class.java.simpleName}")
            }
            SkillImportValidator.validate(skill)
            return skill
        }
    }
}

@Serializable
data class SkillPreview(
    val skill: Skill,
    val sourceUrl: String,
    val rawJson: String
)

class SkillImportException(message: String, cause: Throwable? = null) : IllegalArgumentException(message, cause)
