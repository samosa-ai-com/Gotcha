package com.gotcha.tools

import com.gotcha.data.Settings
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.lang.reflect.Modifier

/**
 * Every [Settings] field is either changeable through `update_gotcha_settings`
 * or listed in [EXCLUDED] with the reason it is not.
 *
 * Settings added after the tool shipped were left out by default, because
 * nothing asked anyone to decide. A new field now fails this test until it
 * is either added to [GotchaSettingsUpdate] or excluded here on purpose.
 *
 * What the tool covers is measured, not declared: every value the schema
 * offers is applied to a default [Settings], and the fields that move are
 * the ones it can change.
 */
class GotchaSettingsCoverageTest {

    private val catalog = SettingsCatalog(
        skins = mapOf("vellum" to "Vellum", "aura" to "Aura"),
        connectors = mapOf("google" to "Google"),
        skills = mapOf("maps" to "Maps")
    )

    /** Values to try for keys whose schema doesn't list them (free text, objects). */
    private val samples: Map<String, List<JsonElement>> = mapOf(
        GotchaSettingsUpdate.CONNECTORS_KEY to listOf(buildJsonObject { put("google", JsonPrimitive(false)) }),
        GotchaSettingsUpdate.SKILLS_KEY to listOf(buildJsonObject { put("maps", JsonPrimitive(false)) })
    )

    private fun valuesToTry(key: String, schema: JsonObject): List<JsonElement> {
        samples[key]?.let { return it }
        schema["enum"]?.jsonArray?.let { return it.toList() }
        return when (schema["type"]?.jsonPrimitive?.content) {
            "boolean" -> listOf(JsonPrimitive(true), JsonPrimitive(false))
            "number" -> listOf(JsonPrimitive(0.0), JsonPrimitive(0.5), JsonPrimitive(1.0))
            else -> fail("No sample value for '$key': add one to `samples`.").let { emptyList() }
        }
    }

    private val fields = Settings::class.java.declaredFields
        .filter { !Modifier.isStatic(it.modifiers) && !it.isSynthetic }
        .onEach { it.isAccessible = true }

    private fun changedFields(before: Settings, after: Settings): Set<String> =
        fields.filter { it.get(before) != it.get(after) }.mapTo(mutableSetOf()) { it.name }

    private fun coveredFields(): Set<String> {
        val base = Settings()
        val covered = mutableSetOf<String>()
        for ((key, schema) in GotchaSettingsUpdate.schemaProperties(catalog)) {
            for (value in valuesToTry(key, schema.jsonObject)) {
                val changes = GotchaSettingsUpdate.parse(buildJsonObject { put(key, value) }, catalog)
                    .getOrElse { fail("'$key' refused its own sample $value: ${it.message}"); return emptySet() }
                covered += changedFields(base, GotchaSettingsUpdate.plan(changes, base).applyTo(base))
            }
        }
        return covered
    }

    @Test
    fun `every setting is either changeable by the assistant or excluded with a reason`() {
        val covered = coveredFields()
        val all = fields.mapTo(sortedSetOf()) { it.name }
        val undecided = all - covered - EXCLUDED.keys
        assertTrue(
            "Settings fields the assistant can't change and nobody decided about: $undecided. " +
                "Add each to GotchaSettingsUpdate, or to EXCLUDED here with the reason.",
            undecided.isEmpty()
        )
    }

    @Test
    fun `nothing is both changeable and excluded`() {
        val both = coveredFields() intersect EXCLUDED.keys
        assertTrue("Excluded but changeable by the assistant — remove from EXCLUDED: $both", both.isEmpty())
    }

    @Test
    fun `every exclusion names a field that exists`() {
        val stale = EXCLUDED.keys - fields.mapTo(mutableSetOf()) { it.name }
        assertTrue("EXCLUDED lists fields Settings no longer has: $stale", stale.isEmpty())
    }

    private companion object {
        const val CREDENTIAL = "a credential or sign-in"
        const val BACKEND = "the model, provider or endpoint: a model that could change these could redirect itself"
        const val LIMIT = "a run limit the model could raise to give itself more budget"
        const val BOOKKEEPING = "app bookkeeping, not a choice the user makes"
        const val PROFILE = "set through update_user_profile instead"
        const val NOT_YET = "safe to offer, not offered yet"

        /** Field → why `update_gotcha_settings` deliberately can't change it. */
        val EXCLUDED: Map<String, String> = mapOf(
            "provider" to BACKEND,
            "apiKey" to CREDENTIAL,
            "baseUrl" to BACKEND,
            "model" to BACKEND,
            "samosaSessionToken" to CREDENTIAL,
            "samosaEmail" to CREDENTIAL,
            "samosaDisplayName" to CREDENTIAL,
            "subAgentModel" to BACKEND,
            "navigatorModel" to BACKEND,
            "maxToolRounds" to LIMIT,
            "maxRepeatedToolCalls" to LIMIT,
            "maxNavigationToolCalls" to LIMIT,
            "maxConsecutiveDelegations" to LIMIT,
            "maxContextTokens" to LIMIT,
            "apiTimeoutSeconds" to LIMIT,
            "ttsProvider" to BACKEND,
            "ttsApiBaseUrl" to BACKEND,
            "ttsApiKey" to CREDENTIAL,
            "ttsApiModel" to BACKEND,
            "sttProvider" to BACKEND,
            "sttApiBaseUrl" to BACKEND,
            "sttApiKey" to CREDENTIAL,
            "sttApiModel" to BACKEND,
            "sttLanguage" to "an override of voice_language for API speech recognition; voice_language covers it",
            "androidSttService" to BACKEND,
            // The voices need the installed engine's (or the API model's) voice list,
            // which only loads asynchronously; checking a name against it is a follow-up.
            "ttsVoice" to "needs the voice list to check against (follow-up)",
            "podcastHostAVoice" to "needs the voice list to check against (follow-up)",
            "podcastHostBVoice" to "needs the voice list to check against (follow-up)",
            "androidTtsEngine" to "needs the installed engine list to check against (follow-up)",
            "androidTtsVoice" to "needs the voice list to check against (follow-up)",
            "assistiveBallEnabled" to "needs the overlay permission, granted in Settings",
            "wakeWordEnabled" to "needs the microphone and overlay permissions, granted in Settings",
            "serverMessagesLastFetchedAt" to BOOKKEEPING,
            "serverMessagesEtag" to BOOKKEEPING,
            "connectorLastRefreshedAt" to BOOKKEEPING,
            "userName" to PROFILE,
            "userLocation" to PROFILE,
            "userOccupation" to PROFILE,
            "userBackground" to PROFILE,
            "userResponseStyle" to PROFILE,
            "communitySkillHosts" to "where skills are downloaded from: a model could add a host it controls",
            "legalAcceptedVersion" to "the user's own acceptance of the legal terms",
            "hasCompletedOnboarding" to BOOKKEEPING,
            "tourStepId" to BOOKKEEPING,
            "onboardingVersion" to BOOKKEEPING,
            // Safe to offer, but not offered yet.
            "androidSttOnDeviceOnly" to NOT_YET,
            "dailyTipMinuteOfDay" to NOT_YET,
            "quietHoursStartMinute" to NOT_YET,
            "quietHoursEndMinute" to NOT_YET,
            "pauseNightLightForScreenshots" to NOT_YET,
            "preferredCurrency" to NOT_YET,
            "connectorAutoRefreshIntervalMinutes" to NOT_YET,
            "proactiveAppBlacklist" to NOT_YET
        )
    }
}
