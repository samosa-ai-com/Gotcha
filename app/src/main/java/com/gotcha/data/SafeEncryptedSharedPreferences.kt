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
 *
 * **Every store gets its own master-key alias** (derived from its file name), never the shared
 * `MasterKey.DEFAULT_MASTER_KEY_ALIAS`. Without this, one store's recovery deletes the one key
 * that all stores depend on — the other stores' Tink keysets become undecryptable and cascade-wipe
 * in turn (credentials, appearance, and the notification delivery log all vanishing at once).
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

        // Delete only this store's own key. The alias is derived from the file
        // name so a corruption in one store can never invalidate the keys that
        // other stores are encrypted under.
        try {
            val keyStore = KeyStore.getInstance("AndroidKeyStore")
            keyStore.load(null)
            keyStore.deleteEntry(masterKeyAlias(fileName))
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
        val masterKey = MasterKey.Builder(context, masterKeyAlias(fileName))
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

    /** Per-store master-key alias so stores are isolated from each other's corruption. */
    private fun masterKeyAlias(fileName: String): String = "_gotcha_master_$fileName"
}
