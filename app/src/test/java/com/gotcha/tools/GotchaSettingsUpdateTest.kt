package com.gotcha.tools

import com.gotcha.data.CompletionPreview
import com.gotcha.data.Settings
import com.gotcha.data.WakeWordListeningMode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The allowlist and validation behind `update_gotcha_settings` (issue #99). Pure
 * logic: the catalog of skins, connectors and skills is handed in.
 */
class GotchaSettingsUpdateTest {

    private val catalog = SettingsCatalog(
        skins = mapOf("vellum" to "Vellum", "aura" to "Aura"),
        connectors = mapOf("google" to "Google", "notion" to "Notion"),
        skills = mapOf("maps" to "Maps")
    )

    private val current = Settings()

    private fun obj(json: String): JsonObject = Json.parseToJsonElement(json).jsonObject

    private fun parse(json: String) = GotchaSettingsUpdate.parse(obj(json), catalog)

    private fun rejection(json: String): String {
        val result = parse(json)
        assertTrue("expected a rejection for $json", result.isFailure)
        return result.exceptionOrNull()!!.message!!
    }

    private fun applied(json: String): Settings =
        GotchaSettingsUpdate.plan(parse(json).getOrThrow(), current).applyTo(current)

    // ---- accepted values ----

    @Test
    fun `booleans, choices and numbers are applied to their fields`() {
        val updated = applied(
            """{"reply_chime":true,"completion_preview":"FULL","wake_word_mode":"screen_on",
               "wake_word_sensitivity":0.4,"reply_language":"german","skin":"aura"}"""
        )
        assertTrue(updated.notifyChimeEnabled)
        assertEquals(CompletionPreview.FULL, updated.chatCompletionPreview)
        assertEquals(WakeWordListeningMode.SCREEN_ON, updated.wakeWordListeningMode)
        assertEquals(0.4f, updated.wakeWordSensitivity, 0.0001f)
        assertEquals("German", updated.preferredLanguage)
        assertEquals("aura", updated.skinId)
    }

    @Test
    fun `voice language can be set back to following the reply language`() {
        val base = current.copy(voiceLanguage = "Hindi")
        val changes = parse("""{"voice_language":"same_as_replies"}""").getOrThrow()
        assertEquals("", GotchaSettingsUpdate.plan(changes, base).applyTo(base).voiceLanguage)
    }

    @Test
    fun `connector and skill toggles edit the disabled sets`() {
        val base = current.copy(disabledConnectors = setOf("notion"))
        val changes = parse("""{"connectors":{"notion":true,"google":false},"skills":{"maps":false}}""").getOrThrow()
        val updated = GotchaSettingsUpdate.plan(changes, base).applyTo(base)
        assertEquals(setOf("google"), updated.disabledConnectors)
        assertEquals(setOf("maps"), updated.disabledSkills)
    }

    // ---- rejected values ----

    @Test
    fun `keys outside the allowlist are refused, credentials included`() {
        listOf("apiKey", "api_key", "samosaSessionToken", "model", "base_url", "maxToolRounds", "userName")
            .forEach { key ->
                assertTrue(rejection("""{"$key":"x"}""").contains("'$key' is not a setting"))
            }
    }

    @Test
    fun `wrong types, unknown choices and out-of-range numbers are refused`() {
        assertTrue(rejection("""{"reply_chime":"yes"}""").contains("reply_chime"))
        assertTrue(rejection("""{"reply_chime":"true"}""").contains("reply_chime"))
        assertTrue(rejection("""{"completion_preview":"everything"}""").contains("completion_preview"))
        assertTrue(rejection("""{"wake_word_sensitivity":1.5}""").contains("wake_word_sensitivity"))
        assertTrue(rejection("""{"wake_word_sensitivity":"0.5"}""").contains("wake_word_sensitivity"))
        assertTrue(rejection("""{"reply_language":"Klingon"}""").contains("reply_language"))
        assertTrue(rejection("""{"skin":"neon"}""").contains("skin"))
        assertTrue(rejection("""{"reply_chime":null}""").contains("reply_chime"))
    }

    @Test
    fun `unknown connector or skill ids are refused`() {
        assertTrue(rejection("""{"connectors":{"dropbox":true}}""").contains("unknown connector 'dropbox'"))
        assertTrue(rejection("""{"skills":{"nope":true}}""").contains("unknown skill 'nope'"))
        assertTrue(rejection("""{"connectors":{"google":"on"}}""").contains("connectors.google"))
        assertTrue(rejection("""{"connectors":true}""").contains("connectors"))
    }

    @Test
    fun `one bad key refuses the whole request`() {
        val message = rejection("""{"reply_chime":true,"apiKey":"sk-123"}""")
        assertTrue(message.contains("apiKey"))
        // Nothing to apply: the valid half is not returned on its own.
        assertTrue(parse("""{"reply_chime":true,"apiKey":"sk-123"}""").getOrNull() == null)
    }

    @Test
    fun `an empty request is refused`() {
        assertTrue(rejection("{}").contains("no settings"))
    }

    // ---- planning and the prompt ----

    @Test
    fun `changes that match the current value are dropped from the plan`() {
        val changes = parse("""{"reply_vibration":true,"reply_chime":true}""").getOrThrow()
        val plan = GotchaSettingsUpdate.plan(changes, current)
        assertEquals(listOf("reply_chime"), plan.changes.map { it.key })
        assertTrue(GotchaSettingsUpdate.plan(changes.take(1), current).changes.isEmpty())
    }

    @Test
    fun `the prompt shows current and requested values, the reason and the impact`() {
        val changes = parse("""{"reply_chime":true,"proactive_scan_screen":false}""").getOrThrow()
        val text = GotchaSettingsUpdate.describe(GotchaSettingsUpdate.plan(changes, current), "You asked for a chime.")
        assertTrue(text, text.contains("Why: You asked for a chime."))
        assertTrue(text, text.contains("Chime when a reply arrives: Off → On"))
        assertTrue(text, text.contains("Proactive: scan the screen: On → Off"))
        assertTrue(text, text.contains("Affects: privacy, notifications."))
    }

    @Test
    fun `a change touching none of the sensitive areas says so`() {
        val changes = parse("""{"skin":"aura"}""").getOrThrow()
        val text = GotchaSettingsUpdate.describe(GotchaSettingsUpdate.plan(changes, current), null)
        assertTrue(text, text.contains("Skin: Vellum → Aura"))
        assertTrue(text, text.contains("Does not affect privacy, notifications, permissions or device control."))
        assertFalse(text, text.contains("Why:"))
    }

    @Test
    fun `a payload survives the trip through the confirmation`() {
        val changes = parse("""{"reply_chime":true,"connectors":{"google":false}}""").getOrThrow()
        val request = GotchaSettingsUpdate.decodePayload(
            GotchaSettingsUpdate.encodePayload(changes, "because"),
            catalog
        )
        assertNotNull(request)
        assertEquals(listOf("reply_chime", "connectors.google"), request!!.changes.map { it.key })
        assertEquals("because", request.reason)
    }

    @Test
    fun `a tampered payload is not trusted`() {
        val forged = java.util.Base64.getEncoder()
            .encodeToString("""{"changes":{"apiKey":"sk-evil"}}""".toByteArray())
        assertNull(GotchaSettingsUpdate.decodePayload(forged, catalog))
        assertNull(GotchaSettingsUpdate.decodePayload("not base64!!", catalog))
    }

    @Test
    fun `the schema offers exactly the keys the parser accepts`() {
        val schemaKeys = GotchaSettingsUpdate.schemaProperties(catalog).keys
        assertEquals(GotchaSettingsUpdate.keys(catalog).toSet(), schemaKeys)
    }
}
