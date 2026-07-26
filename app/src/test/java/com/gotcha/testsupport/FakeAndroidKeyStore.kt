package com.gotcha.testsupport

import java.io.InputStream
import java.io.OutputStream
import java.security.Key
import java.security.KeyStoreSpi
import java.security.Provider
import java.security.SecureRandom
import java.security.cert.Certificate
import java.security.spec.AlgorithmParameterSpec
import java.util.Collections
import java.util.Date
import javax.crypto.KeyGenerator
import javax.crypto.KeyGeneratorSpi
import javax.crypto.SecretKey

/**
 * An in-memory stand-in for the `AndroidKeyStore` JCE provider.
 *
 * Robolectric does not emulate the Android keystore, so anything reaching
 * `androidx.security.crypto.EncryptedSharedPreferences` — which on this codebase means
 * `ConnectorCredentialStore`, and therefore **`ToolExecutor`'s constructor** via
 * `ConnectorRegistry.init` — dies with `KeyStoreException: AndroidKeyStore not found`.
 * Without this, no Robolectric test can construct a `ToolExecutor` at all.
 *
 * The keys are ordinary in-memory AES keys, so standard JCE ciphers operate on them normally.
 * This is a test fixture: it provides no hardware backing and no security properties.
 *
 * Call [setUp] from `@Before` (it is idempotent).
 */
object FakeAndroidKeyStore {

    private const val PROVIDER_NAME = "AndroidKeyStore"

    private val entries = mutableMapOf<String, Key>()

    fun setUp() {
        if (java.security.Security.getProvider(PROVIDER_NAME) == null) {
            java.security.Security.addProvider(FakeProvider())
        }
    }

    private class FakeProvider : Provider(PROVIDER_NAME, 1.0, "Test-only fake AndroidKeyStore") {
        init {
            put("KeyStore.$PROVIDER_NAME", FakeKeyStoreSpi::class.java.name)
            put("KeyGenerator.AES", FakeAesKeyGeneratorSpi::class.java.name)
        }
    }

    class FakeKeyStoreSpi : KeyStoreSpi() {
        override fun engineGetKey(alias: String, password: CharArray?): Key? = entries[alias]

        override fun engineGetCertificateChain(alias: String): Array<Certificate>? = null

        override fun engineGetCertificate(alias: String): Certificate? = null

        override fun engineGetCreationDate(alias: String): Date = Date(0)

        override fun engineSetKeyEntry(
            alias: String,
            key: Key,
            password: CharArray?,
            chain: Array<out Certificate>?
        ) {
            entries[alias] = key
        }

        override fun engineSetKeyEntry(alias: String, key: ByteArray, chain: Array<out Certificate>?) =
            throw UnsupportedOperationException("encoded key entries are not used by these tests")

        override fun engineSetCertificateEntry(alias: String, cert: Certificate?) =
            throw UnsupportedOperationException("certificate entries are not used by these tests")

        override fun engineDeleteEntry(alias: String) {
            entries.remove(alias)
        }

        override fun engineAliases(): java.util.Enumeration<String> =
            Collections.enumeration(entries.keys.toList())

        override fun engineContainsAlias(alias: String): Boolean = entries.containsKey(alias)

        override fun engineSize(): Int = entries.size

        override fun engineIsKeyEntry(alias: String): Boolean = entries.containsKey(alias)

        override fun engineIsCertificateEntry(alias: String): Boolean = false

        override fun engineGetCertificateAlias(cert: Certificate?): String? = null

        override fun engineStore(stream: OutputStream?, password: CharArray?) = Unit

        override fun engineLoad(stream: InputStream?, password: CharArray?) = Unit
    }

    class FakeAesKeyGeneratorSpi : KeyGeneratorSpi() {
        private var alias: String? = null
        private var keySize = 256

        override fun engineInit(random: SecureRandom?) = Unit

        override fun engineInit(params: AlgorithmParameterSpec?, random: SecureRandom?) {
            // KeyGenParameterSpec carries the alias the caller wants the key stored under.
            alias = runCatching {
                params?.javaClass?.getMethod("getKeystoreAlias")?.invoke(params) as? String
            }.getOrNull()
            val size = runCatching {
                params?.javaClass?.getMethod("getKeySize")?.invoke(params) as? Int
            }.getOrNull()
            if (size != null && size > 0) keySize = size
        }

        override fun engineInit(keysize: Int, random: SecureRandom?) {
            keySize = keysize
        }

        override fun engineGenerateKey(): SecretKey {
            val generator = KeyGenerator.getInstance("AES")
            generator.init(keySize)
            val key = generator.generateKey()
            alias?.let { entries[it] = key }
            return key
        }
    }

    /** Drops every generated key. Call from `@After` if a test needs a clean keystore. */
    fun clear() {
        entries.clear()
    }
}
