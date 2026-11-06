package com.gotcha.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The one-time "new for you" flags on the home screen. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FeatureIntroStoreTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var store: FeatureIntroStore

    @Before
    fun setUp() {
        context.getSharedPreferences(FeatureIntroStore.FILE, Context.MODE_PRIVATE).edit().clear().commit()
        store = FeatureIntroStore(context)
    }

    @Test
    fun `a fresh install has seen neither feature`() {
        assertFalse(store.hasSeen(FeatureIntroStore.MEMORY))
        assertFalse(store.hasSeen(FeatureIntroStore.SCHEDULE))
    }

    @Test
    fun `marking one feature leaves the other alone`() {
        store.markSeen(FeatureIntroStore.MEMORY)

        assertTrue(store.hasSeen(FeatureIntroStore.MEMORY))
        assertFalse(store.hasSeen(FeatureIntroStore.SCHEDULE))
    }

    @Test
    fun `flags survive a new store over the same prefs`() {
        store.markSeen(FeatureIntroStore.SCHEDULE)

        assertTrue(FeatureIntroStore(context).hasSeen(FeatureIntroStore.SCHEDULE))
    }
}
