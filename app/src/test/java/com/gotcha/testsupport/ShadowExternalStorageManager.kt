package com.gotcha.testsupport

import android.os.Environment
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowEnvironment

/**
 * Robolectric 4.14 does not shadow [Environment.isExternalStorageManager]; the real framework
 * implementation reaches for storage volumes that the test runtime never populates and throws
 * `ArrayIndexOutOfBoundsException`. `FileResolver` calls it on every resolve, so without this
 * shadow no file-tool test can run at all.
 *
 * Registering it also makes "All files access" a *controllable* input, so tests can cover both
 * sides of the permission branch instead of only the denied one:
 *
 * ```
 * @Config(shadows = [ShadowExternalStorageManager::class])
 * ...
 * ShadowExternalStorageManager.granted = true
 * ```
 *
 * Extends [ShadowEnvironment] rather than replacing it: a bare `@Implements(Environment)`
 * shadow takes over the whole class, so `getExternalStorageDirectory()` and friends would fall
 * through to the real framework and throw as well.
 *
 * Reset it in `@After` — Robolectric does not reset custom shadow statics between tests.
 */
@Implements(Environment::class)
class ShadowExternalStorageManager : ShadowEnvironment() {

    companion object {
        /**
         * What [Environment.isExternalStorageManager] should report. Defaults to denied.
         * Named to avoid a JVM signature clash with the `@Implementation` method below.
         */
        @JvmStatic
        var granted: Boolean = false

        @JvmStatic
        @Implementation
        fun isExternalStorageManager(): Boolean = granted

        /** Restores the default (denied). Call from `@After`. */
        @JvmStatic
        fun resetGranted() {
            granted = false
        }
    }
}
