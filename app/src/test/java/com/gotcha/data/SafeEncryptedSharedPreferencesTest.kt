package com.gotcha.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.gotcha.testsupport.FakeAndroidKeyStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SafeEncryptedSharedPreferencesTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        FakeAndroidKeyStore.setUp()
        context = ApplicationProvider.getApplicationContext()
    }

    @Test
    fun `create returns functional shared preferences`() {
        val prefs = SafeEncryptedSharedPreferences.create(context, "test_safe_prefs")
        assertNotNull(prefs)
        prefs.edit().putString("test_key", "test_val").commit()
        assertEquals("test_val", prefs.getString("test_key", null))
    }

    @Test
    fun `recovery path handles corrupted preferences cleanly`() {
        val prefName = "test_corrupt_prefs"
        // Seed corrupt/malformed XML file
        val rawPrefs = context.getSharedPreferences(prefName, Context.MODE_PRIVATE)
        rawPrefs.edit().putString("corrupted_data", "invalid_encrypted_format").commit()

        val safePrefs = SafeEncryptedSharedPreferences.create(context, prefName)
        assertNotNull(safePrefs)
        safePrefs.edit().putString("recovered_key", "recovered_val").commit()
        assertEquals("recovered_val", safePrefs.getString("recovered_key", null))
    }
}
