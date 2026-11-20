package org.skepsun.kototoro.community.nostr

/** NIP-19 bare keys: `npub1…` for a public key and `nsec1…` for the secret a reader keeps as their recovery key. */
object NostrBech32 {

    const val PUBLIC_PREFIX = "npub"
    const val SECRET_PREFIX = "nsec"

    fun encodePublicKey(hex: String): String = encode(PUBLIC_PREFIX, requireNotNull(hex.hexToBytesOrNull()) { "not hex" })

    fun encodeSecretKey(keys: NostrKeyPair): String = encode(SECRET_PREFIX, NostrKeyPair.secretOf(keys))

    /** The 32-byte key under [prefix] as hex, or null for a wrong prefix, a damaged checksum or the wrong length. */
    fun decode(value: String, prefix: String): String? {
        val (hrp, data) = decodeRaw(value.filterNot { it.isWhitespace() }) ?: return null
        if (hrp != prefix) return null
        return data.takeIf { it.size == 32 }?.toHex()
    }

    private const val CHARSET = "qpzry9x8gf2tvdw0s3jn54khce6mua7l"
    private val GENERATOR = intArrayOf(0x3b6a57b2, 0x26508e6d, 0x1ea119fa, 0x3d4233dd, 0x2a1462b3)

    private fun polymod(values: IntArray): Int {
        var checksum = 1
        for (value in values) {
            val top = checksum ushr 25
            checksum = ((checksum and 0x1ffffff) shl 5) xor value
            for (i in 0 until 5) {
                if ((top ushr i) and 1 != 0) checksum = checksum xor GENERATOR[i]
            }
        }
        return checksum
    }

    private fun expandHrp(hrp: String): IntArray =
        IntArray(hrp.length * 2 + 1) { i ->
            when {
                i < hrp.length -> hrp[i].code ushr 5
                i == hrp.length -> 0
                else -> hrp[i - hrp.length - 1].code and 31
            }
        }

    private fun encode(hrp: String, bytes: ByteArray): String {
        val data = convertBits(bytes.map { it.toInt() and 0xFF }, 8, 5, pad = true)!!
        val checksumInput = expandHrp(hrp) + data.toIntArray() + IntArray(6)
        val checksum = polymod(checksumInput) xor 1
        return buildString {
            append(hrp).append('1')
            data.forEach { append(CHARSET[it]) }
            for (i in 0 until 6) append(CHARSET[(checksum ushr (5 * (5 - i))) and 31])
        }
    }

    private fun decodeRaw(value: String): Pair<String, ByteArray>? {
        if (value.lowercase() != value && value.uppercase() != value) return null
        val text = value.lowercase()
        val separator = text.lastIndexOf('1')
        if (separator < 1 || separator + 7 > text.length) return null
        val hrp = text.substring(0, separator)
        val values = text.substring(separator + 1).map { CHARSET.indexOf(it).takeIf { index -> index >= 0 } ?: return null }
        if (polymod(expandHrp(hrp) + values.toIntArray()) != 1) return null
        val bytes = convertBits(values.dropLast(6), 5, 8, pad = false) ?: return null
        return hrp to ByteArray(bytes.size) { bytes[it].toByte() }
    }

    private fun convertBits(data: List<Int>, from: Int, to: Int, pad: Boolean): List<Int>? {
        var accumulator = 0
        var bits = 0
        val maxValue = (1 shl to) - 1
        val result = ArrayList<Int>()
        for (value in data) {
            if (value < 0 || value ushr from != 0) return null
            accumulator = (accumulator shl from) or value
            bits += from
            while (bits >= to) {
                bits -= to
                result += (accumulator ushr bits) and maxValue
            }
        }
        if (pad) {
            if (bits > 0) result += (accumulator shl (to - bits)) and maxValue
        } else if (bits >= from || (accumulator shl (to - bits)) and maxValue != 0) {
            return null
        }
        return result
    }
}
