package org.skepsun.kototoro.core.prefs

import org.skepsun.kototoro.parsers.util.md5
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * The stored form of the app lock password: salted PBKDF2-HMAC-SHA256 (`pbkdf2-sha256$iterations$salt$hash`).
 * Earlier versions stored an unsalted MD5, which a precomputed table reverses at once for a numeric PIN; such a value
 * still verifies, and [needsUpgrade] tells the caller to store the new form after a successful unlock.
 */
object AppPasswordHash {
    private const val PREFIX = "pbkdf2-sha256"
    private const val ITERATIONS = 120_000
    private const val KEY_BITS = 256

    fun hash(password: String, salt: ByteArray = ByteArray(16).also(SecureRandom()::nextBytes),
             iterations: Int = ITERATIONS): String {
        val encoder = Base64.getEncoder().withoutPadding()
        return "$PREFIX$$iterations$${encoder.encodeToString(salt)}$${encoder.encodeToString(derive(password, salt, iterations))}"
    }

    fun verify(password: String, stored: String?): Boolean {
        if (stored.isNullOrEmpty()) return false
        val parts = stored.split('$')
        if (parts.size == 4 && parts[0] == PREFIX) {
            val iterations = parts[1].toIntOrNull() ?: return false
            val decoder = Base64.getDecoder()
            val salt = runCatching { decoder.decode(parts[2]) }.getOrNull() ?: return false
            val expected = runCatching { decoder.decode(parts[3]) }.getOrNull() ?: return false
            return MessageDigest.isEqual(derive(password, salt, iterations), expected)
        }
        // The legacy unsalted MD5.
        return MessageDigest.isEqual(password.md5().toByteArray(), stored.toByteArray())
    }

    fun needsUpgrade(stored: String?): Boolean = !stored.isNullOrEmpty() && !stored.startsWith("$PREFIX$")

    private fun derive(password: String, salt: ByteArray, iterations: Int): ByteArray {
        val spec = PBEKeySpec(password.toCharArray(), salt, iterations, KEY_BITS)
        return try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }
}
