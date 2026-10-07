package com.gotcha.testsupport

import androidx.core.content.FileProvider

/**
 * AndroidX `FileProvider` caches its parsed `file_paths.xml` strategy in a
 * static map keyed by authority. Every Robolectric test gets a fresh
 * application sandbox, but the JVM (and the cache) is shared across the
 * whole Gradle test task — so whichever test calls `getUriForFile` first
 * pins every later test's lookups to ITS sandbox, and files from other
 * sandboxes wrongly resolve as "outside the mapped roots".
 *
 * Call [clear] from `@Before` (and `@After`, for the next class) in every
 * test that touches `FileProvider`, same discipline as
 * [ShadowExternalStorageManager.resetGranted].
 */
object FileProviderCache {
    fun clear() {
        val field = FileProvider::class.java.getDeclaredField("sCache").apply {
            isAccessible = true
        }
        @Suppress("UNCHECKED_CAST")
        (field.get(null) as MutableMap<*, *>).clear()
    }
}
