package com.gotcha.telemetry

import androidx.test.core.app.ApplicationProvider
import com.gotcha.BuildConfig
import com.gotcha.agent.skills.SkillRegistry
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TelemetryVocabularyTest {
    @Test
    fun exportMatchesSchemaAndIsStable() {
        SkillRegistry.init(ApplicationProvider.getApplicationContext())
        assertTrue("bundled skills did not load", SkillRegistry.bundledSkillIds().isNotEmpty())

        val vocabulary = TelemetryVocabulary.build(BuildConfig.VERSION_CODE)
        val events = vocabulary["events"] as JsonObject
        assertEquals(TelemetrySchema.eventNames.toSet(), events.keys)
        TelemetrySchema.eventNames.forEach { event ->
            TelemetrySchema.propsOf(event).orEmpty().forEach { (name, prop) ->
                if (prop.vocab is Vocab.Dynamic) {
                    val words = prop.vocab.describe()["words"] as JsonArray
                    assertFalse("$event.$name resolved to no words", words.isEmpty())
                }
            }
        }

        val first = TelemetryVocabulary.render(BuildConfig.VERSION_CODE).toByteArray(Charsets.UTF_8)
        val second = TelemetryVocabulary.render(BuildConfig.VERSION_CODE).toByteArray(Charsets.UTF_8)
        assertArrayEquals(first, second)

        val output = System.getProperty("gotcha.telemetry.output")?.let(::File)
        if (output != null) {
            output.parentFile?.mkdirs()
            output.writeBytes(first)
            System.getProperty("gotcha.telemetry.expected")?.let { expected ->
                assertTrue("committed vocabulary missing: $expected", File(expected).isFile)
                assertArrayEquals(
                    "committed telemetry vocabulary differs",
                    File(expected).readBytes(),
                    output.readBytes()
                )
            }
        }
    }
}
