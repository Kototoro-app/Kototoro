package org.skepsun.kototoro.community.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import org.skepsun.kototoro.community.nostr.NostrBech32
import org.skepsun.kototoro.core.library.community.CommunityIdentityStore
import org.skepsun.kototoro.community.nostr.NostrKeyPair
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The reader's community identity (plan §2). The secret is sealed with an AES-GCM key that never leaves the Android
 * Keystore, so a copied preferences file is useless without this device; it is not part of backups, and the reader keeps
 * it as the `nsec` recovery key instead.
 */
@Singleton
class CommunityKeyStore @Inject constructor(@ApplicationContext context: Context) : CommunityIdentityStore {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    @Volatile
    private var cached: NostrKeyPair? = null

    val publicKeyHex: String? get() = prefs.getString(KEY_PUBLIC, null)

    override val hasIdentity: Boolean get() = publicKeyHex != null

    /** The identity, unsealed once per process; null when there is none or the Keystore key is gone (device reset). */
    override fun keys(): NostrKeyPair? {
        cached?.let { return it }
        val sealed = prefs.getString(KEY_SEALED, null) ?: return null
        val iv = prefs.getString(KEY_IV, null) ?: return null
        val secret = runCatching {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, existingKey() ?: return null, GCMParameterSpec(TAG_BITS, decode(iv)))
            String(cipher.doFinal(decode(sealed)), Charsets.US_ASCII)
        }.getOrNull() ?: return null
        return NostrKeyPair.fromSecretHex(secret)?.also { cached = it }
    }

    fun create(): NostrKeyPair = NostrKeyPair.generate().also(::store)

    /** From a recovery key a reader pasted; null when it is not one. */
    fun restore(nsec: String): NostrKeyPair? =
        NostrBech32.decode(nsec, NostrBech32.SECRET_PREFIX)?.let(NostrKeyPair::fromSecretHex)?.also(::store)

    fun recoveryKey(): String? = keys()?.let(NostrBech32::encodeSecretKey)

    override fun clear() {
        cached = null
        prefs.edit { clear() }
        runCatching { keyStore().deleteEntry(ALIAS) }
    }

    override fun store(keys: NostrKeyPair) {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, existingKey() ?: newKey())
        val sealed = cipher.doFinal(keys.secretKeyHex.toByteArray(Charsets.US_ASCII))
        prefs.edit {
            putString(KEY_PUBLIC, keys.publicKeyHex)
            putString(KEY_SEALED, encode(sealed))
            putString(KEY_IV, encode(cipher.iv))
        }
        cached = keys
    }

    private fun keyStore() = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    private fun existingKey(): SecretKey? = (keyStore().getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.secretKey

    private fun newKey(): SecretKey = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE).apply {
        init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
    }.generateKey()

    private fun encode(bytes: ByteArray) = Base64.encodeToString(bytes, Base64.NO_WRAP)

    private fun decode(text: String) = Base64.decode(text, Base64.NO_WRAP)

    private companion object {
        const val PREFS = "community_identity"
        const val KEY_PUBLIC = "public_key"
        const val KEY_SEALED = "sealed_secret"
        const val KEY_IV = "iv"
        const val ALIAS = "kototoro_community_identity"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val TAG_BITS = 128
    }
}
