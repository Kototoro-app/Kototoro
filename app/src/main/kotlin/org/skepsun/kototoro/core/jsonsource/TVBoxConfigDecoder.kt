package org.skepsun.kototoro.core.jsonsource

import java.nio.charset.StandardCharsets
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Decodes the configuration wrappers supported by the mainstream TVBox clients. */
internal object TVBoxConfigDecoder {

    private val base64Marker = Regex("[A-Za-z0-9]{8}\\*\\*")

    fun decode(rawContent: String): String {
        var content = rawContent
        val marker = base64Marker.find(content)
        if (marker != null) {
            content = String(
                Base64.getMimeDecoder().decode(content.substring(marker.range.last + 1)),
                StandardCharsets.UTF_8,
            )
        }

        content = content.trim()
        if (!content.startsWith(HEX_ENCRYPTED_PREFIX)) {
            return content
        }
        return decodeAesCbc(content.filterNot(Char::isWhitespace))
    }

    private fun decodeAesCbc(content: String): String {
        require(content.length % 2 == 0) { "Invalid TVBox encrypted configuration length" }
        val decodedEnvelope = String(content.hexToBytes(), StandardCharsets.UTF_8).lowercase()
        val keyStart = decodedEnvelope.indexOf(KEY_PREFIX)
        val keyEnd = decodedEnvelope.indexOf(KEY_SUFFIX, keyStart + KEY_PREFIX.length)
        require(keyStart >= 0 && keyEnd > keyStart) { "Invalid TVBox encrypted configuration key" }
        require(decodedEnvelope.length >= IV_SEED_LENGTH) { "Invalid TVBox encrypted configuration IV" }

        val encryptedStart = content.indexOf(KEY_SUFFIX_HEX)
        require(encryptedStart >= 0 && content.length > encryptedStart + KEY_SUFFIX_HEX.length + IV_SEED_HEX_LENGTH) {
            "Invalid TVBox encrypted configuration payload"
        }
        val encrypted = content.substring(
            startIndex = encryptedStart + KEY_SUFFIX_HEX.length,
            endIndex = content.length - IV_SEED_HEX_LENGTH,
        ).hexToBytes()
        val key = decodedEnvelope.substring(keyStart + KEY_PREFIX.length, keyEnd).padEnd(AES_BLOCK_LENGTH, '0')
        val iv = decodedEnvelope.takeLast(IV_SEED_LENGTH).padEnd(AES_BLOCK_LENGTH, '0')
        require(key.length == AES_BLOCK_LENGTH && iv.length == AES_BLOCK_LENGTH) {
            "Invalid TVBox encrypted configuration key or IV length"
        }

        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(key.toByteArray(StandardCharsets.UTF_8), "AES"),
            IvParameterSpec(iv.toByteArray(StandardCharsets.UTF_8)),
        )
        return String(cipher.doFinal(encrypted), StandardCharsets.UTF_8)
    }

    private fun String.hexToBytes(): ByteArray = ByteArray(length / 2) { index ->
        substring(index * 2, index * 2 + 2).toInt(16).toByte()
    }

    private const val HEX_ENCRYPTED_PREFIX = "2423"
    private const val KEY_PREFIX = "$#"
    private const val KEY_SUFFIX = "#$"
    private const val KEY_SUFFIX_HEX = "2324"
    private const val AES_BLOCK_LENGTH = 16
    private const val IV_SEED_LENGTH = 13
    private const val IV_SEED_HEX_LENGTH = IV_SEED_LENGTH * 2
}
