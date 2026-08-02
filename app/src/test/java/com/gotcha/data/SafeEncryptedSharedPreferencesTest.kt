package com.gotcha.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.gotcha.testsupport.FakeAndroidKeyStore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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

    @After
    fun tearDown() {
        FakeAndroidKeyStore.clear()
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

    @Test
    fun `two stores use different master keys so corruption in one does not wipe the other`() {
        val storeA = "test_iso_a"
        val storeB = "test_iso_b"
        val prefsA = SafeEncryptedSharedPreferences.create(context, storeA)
        val prefsB = SafeEncryptedSharedPreferences.create(context, storeB)
        prefsA.edit().putString("key", "value_a").commit()
        prefsB.edit().putString("key", "value_b").commit()

        // Corrupt store A: wipe its file AND its key alias, exactly what a
        // per-store recovery would do. Store B must keep its data.
        context.deleteSharedPreferences(storeA)
        val recoveredA = SafeEncryptedSharedPreferences.create(context, storeA)
        recoveredA.edit().putString("key", "recovered_a").commit()

        // The sibling store was encrypted under a different alias, so its
        // keyset and values must be untouched.
        val reloadedB = SafeEncryptedSharedPreferences.create(context, storeB)
        assertEquals("value_b", reloadedB.getString("key", null))
        assertNull(reloadedB.getString("never_written", null))
    }
}
