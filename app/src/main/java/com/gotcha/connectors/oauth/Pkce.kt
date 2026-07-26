package com.gotcha.connectors.oauth

import java.security.MessageDigest
import java.security.SecureRandom

/** PKCE (RFC 7636) code verifier + S256 challenge generation. */
object Pkce {

    private val verifierChars =
        ('A'..'Z') + ('a'..'z') + ('0'..'9') + listOf('-', '.', '_', '~')

    fun generateVerifier(length: Int = 64): String {
        require(length in 43..128) { "RFC 7636 verifier length must be 43-128" }
        val random = SecureRandom()
        return buildString(length) {
            repeat(length) { append(verifierChars[random.nextInt(verifierChars.size)]) }
        }
    }

    /** S256: BASE64URL-ENCODE(SHA256(ASCII(verifier))) without padding. */
    fun challengeS256(verifier: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(verifier.toByteArray(Charsets.US_ASCII))
        return base64UrlNoPadding(digest)
    }

    fun base64UrlNoPadding(bytes: ByteArray): String =
        java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
}
