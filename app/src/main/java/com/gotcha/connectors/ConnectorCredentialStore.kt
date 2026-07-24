package com.gotcha.connectors

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/** Raw per-connector blob storage, implemented by the encrypted prod store and by test fakes. */
interface CredentialStore {
    fun loadRaw(connectorId: String): String?
    fun saveRaw(connectorId: String, blob: String)
    fun clear(connectorId: String)
}

/**
 * Encrypted storage for connector credentials, kept in a separate file from
 * app settings. One JSON blob per connector id; each connector encodes/decodes
 * its own @Serializable credential class. Never logged.
 */
class ConnectorCredentialStore(context: Context) : CredentialStore {

    private val prefs: SharedPreferences by lazy {
        val masterKey = MasterKey.Builder(context.applicationContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context.applicationContext,
            "gotcha_connectors",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    override fun loadRaw(connectorId: String): String? = prefs.getString(connectorId, null)

    override fun saveRaw(connectorId: String, blob: String) {
        prefs.edit().putString(connectorId, blob).apply()
    }

    override fun clear(connectorId: String) {
        prefs.edit().remove(connectorId).apply()
    }
}
