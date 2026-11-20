package org.skepsun.kototoro.community.nostr

import fr.acinq.secp256k1.Secp256k1
import org.kotlincrypto.random.CryptoRand

/**
 * A community identity: a secp256k1 secret and its BIP-340 x-only public key. The secret never leaves the device except
 * as the reader's own recovery key ([NostrBech32.encodeSecretKey]); the platform stores it encrypted.
 */
class NostrKeyPair private constructor(private val secret: ByteArray) {

    val publicKeyHex: String = Secp256k1.pubkeyCreate(secret).copyOfRange(1, 33).toHex()

    val secretKeyHex: String get() = secret.toHex()

    override fun toString(): String = "NostrKeyPair($publicKeyHex)"

    companion object {

        fun generate(): NostrKeyPair {
            while (true) {
                val candidate = CryptoRand.Default.nextBytes(ByteArray(32))
                if (Secp256k1.secKeyVerify(candidate)) return NostrKeyPair(candidate)
            }
        }

        /** From 64 hex characters; null for anything that is not a valid secret. */
        fun fromSecretHex(hex: String): NostrKeyPair? {
            val bytes = hex.trim().lowercase().hexToBytesOrNull()?.takeIf { it.size == 32 } ?: return null
            return if (Secp256k1.secKeyVerify(bytes)) NostrKeyPair(bytes) else null
        }

        internal fun secretOf(keys: NostrKeyPair): ByteArray = keys.secret
    }
}

object NostrKeys {

    /** Signs [event] as [keys], with fresh auxiliary randomness unless a test passes its own. */
    fun sign(event: NostrUnsignedEvent, keys: NostrKeyPair, auxRand: ByteArray? = CryptoRand.Default.nextBytes(ByteArray(32))): NostrEvent {
        val id = NostrEvent.computeId(keys.publicKeyHex, event.createdAt, event.kind, event.tags, event.content)
        val sig = Secp256k1.signSchnorr(id.hexToBytesOrNull()!!, NostrKeyPair.secretOf(keys), auxRand).toHex()
        return NostrEvent(id, keys.publicKeyHex, event.createdAt, event.kind, event.tags, event.content, sig)
    }

    /** Whether the id is the event's hash and the signature is its author's. Anything malformed is simply invalid. */
    fun verify(event: NostrEvent): Boolean {
        if (!event.hasValidId()) return false
        val id = event.id.hexToBytesOrNull()?.takeIf { it.size == 32 } ?: return false
        val pubkey = event.pubkey.hexToBytesOrNull()?.takeIf { it.size == 32 } ?: return false
        val sig = event.sig.hexToBytesOrNull()?.takeIf { it.size == 64 } ?: return false
        return runCatching { Secp256k1.verifySchnorr(sig, id, pubkey) }.getOrDefault(false)
    }
}
