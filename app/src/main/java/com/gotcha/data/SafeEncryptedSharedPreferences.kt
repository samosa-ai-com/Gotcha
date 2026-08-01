package com.gotcha.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.security.KeyStore

/**
 * Factory for creating [EncryptedSharedPreferences] instances safely.
 *
 * If Android KeyStore key entries or the stored Tink keyset become corrupted or
 * unreadable (e.g. after app reinstall, OS update, device restore, or key invalidation),
 * [EncryptedSharedPreferences.create] throws an exception (such as [javax.crypto.AEADBadTagException]
 * or [java.security.KeyStoreException]).
 *
 * This factory catches such errors, clears the corrupted shared preference file and master key,
 * and recreates fresh encrypted preferences. If recovery still fails, it falls back to standard
 * unencrypted [SharedPreferences] to ensure the application does not crash on startup.
 */
object SafeEncryptedSharedPreferences {
    private const val TAG = "SafeEncryptedPrefs"

    fun create(context: Context, fileName: String): SharedPreferences {
        val appContext = context.applicationContext
        return try {
            buildEncryptedPrefs(appContext, fileName)
        } catch (e: Throwable) {
            Log.w(
                TAG,
                "EncryptedSharedPreferences creation failed for '$fileName'. Attempting recovery.",
                e
            )
            recover(appContext, fileName)
        }
    }

    private fun recover(context: Context, fileName: String): SharedPreferences {
        try {
            context.deleteSharedPreferences(fileName)
        } catch (delErr: Exception) {
            Log.w(TAG, "Failed to delete corrupt SharedPreferences file '$fileName'", delErr)
        }

        try {
            val keyStore = KeyStore.getInstance("AndroidKeyStore")
            keyStore.load(null)
            keyStore.deleteEntry(MasterKey.DEFAULT_MASTER_KEY_ALIAS)
        } catch (ksErr: Exception) {
            Log.w(TAG, "Failed to clear master key alias from AndroidKeyStore", ksErr)
        }

        return try {
            buildEncryptedPrefs(context, fileName)
        } catch (retryErr: Throwable) {
            Log.e(
                TAG,
                "Retry creating EncryptedSharedPreferences for '$fileName' failed. " +
                    "Falling back to unencrypted SharedPreferences.",
                retryErr
            )
            context.getSharedPreferences(fileName, Context.MODE_PRIVATE)
        }
    }

    private fun buildEncryptedPrefs(context: Context, fileName: String): SharedPreferences {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        return EncryptedSharedPreferences.create(
            context,
            fileName,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }
}
