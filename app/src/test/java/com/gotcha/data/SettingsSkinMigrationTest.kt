package com.gotcha.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.gotcha.testsupport.FakeAndroidKeyStore
import com.gotcha.ui.theme.Skins
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The retired faceted theme ("nocturne") migrates to Samosa at the data layer,
 * so every reader of the stored setting sees the replacement id without the
 * user having to re-select an already-selected tile.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SettingsSkinMigrationTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var repository: SettingsRepository

    @Before
    fun setUp() {
        FakeAndroidKeyStore.setUp()
        // Hermetic prefs: the whole suite shares one Robolectric JVM, and other
        // classes leave encrypted settings behind that this class must not see.
        context.getSharedPreferences(SETTINGS_PREFS_FILE, Context.MODE_PRIVATE)
            .edit().clear().apply()
        repository = SettingsRepository(context)
    }

    @Test
    fun `a stored nocturne id loads as samosa`() {
        repository.save(Settings(skinId = "nocturne"))

        assertEquals(Skins.Samosa.id, repository.load().skinId)
        assertEquals(Skins.Samosa.id, repository.loadSkinId())
    }

    @Test
    fun `the migration persists, so the stale id is gone on the next load`() {
        repository.save(Settings(skinId = "nocturne"))
        repository.load()

        assertEquals(Skins.Samosa.id, SettingsRepository(context).loadSkinId())
    }

    @Test
    fun `other stored ids pass through untouched`() {
        repository.save(Settings(skinId = Skins.Orchid.id))

        assertEquals(Skins.Orchid.id, repository.loadSkinId())
    }
}
