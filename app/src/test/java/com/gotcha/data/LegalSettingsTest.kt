package com.gotcha.data

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.gotcha.testsupport.FakeAndroidKeyStore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Round-trip for [Settings.legalAcceptedVersion], which is the e-tag the
 * first-launch consent dialog uses to decide whether to re-prompt.
 *
 * Bumping [LEGAL_VERSION] in code is what forces re-acceptance on every
 * install; [SettingsRepository.save] must persist the new tag, and
 * [SettingsRepository.load] must read it back so the gate closes on the
 * next launch.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LegalSettingsTest {

    private val application: Application = ApplicationProvider.getApplicationContext()
    private lateinit var repository: SettingsRepository

    @Before
    fun setUp() {
        FakeAndroidKeyStore.setUp()
        repository = SettingsRepository(application)
        // Fresh settings for every test — important: the consent flag lives
        // in the encrypted prefs file alongside everything else, so a stale
        // value from a previous test would mask the assertion.
        repository.save(Settings(apiKey = "test-key"))
    }

    @After
    fun tearDown() {
        repository.save(
            Settings(apiKey = "test-key").copy(legalAcceptedVersion = "")
        )
    }

    @Test
    fun `default settings have an empty legal accepted version`() {
        assertEquals("", repository.load().legalAcceptedVersion)
    }

    @Test
    fun `saving the current LEGAL_VERSION closes the gate on next load`() {
        // Simulates tapping "I agree" in the first-launch dialog.
        repository.save(
            repository.load().copy(legalAcceptedVersion = LEGAL_VERSION)
        )

        val loaded = repository.load()

        assertEquals(LEGAL_VERSION, loaded.legalAcceptedVersion)
        // The gate-closed predicate the activity uses:
        assertEquals(LEGAL_VERSION, loaded.legalAcceptedVersion)
    }

    @Test
    fun `save and load preserves the stored version across writes`() {
        repository.save(repository.load().copy(legalAcceptedVersion = LEGAL_VERSION))
        // Write a different field to confirm the legal tag isn't disturbed
        // by an unrelated save.
        repository.save(
            repository.load().copy(model = "gpt-4o-mini")
        )

        val loaded = repository.load()

        assertEquals(LEGAL_VERSION, loaded.legalAcceptedVersion)
        assertEquals("gpt-4o-mini", loaded.model)
    }

    @Test
    fun `an older accepted version still triggers the gate`() {
        // Simulates a downgrade scenario (e.g. an upgrade that reverts the
        // version constant). The stored value must come back exactly.
        repository.save(repository.load().copy(legalAcceptedVersion = "0"))

        val loaded = repository.load()

        assertEquals("0", loaded.legalAcceptedVersion)
        assertTrue(loaded.legalAcceptedVersion != LEGAL_VERSION)
    }
}
