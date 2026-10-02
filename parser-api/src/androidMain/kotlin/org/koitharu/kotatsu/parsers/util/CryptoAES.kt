package org.koitharu.kotatsu.parsers.util

import org.koitharu.kotatsu.parsers.InternalParsersApi
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

private const val HASH_CIPHER = "AES/CBC/PKCS7PADDING"
private const val AES = "AES"
private const val KDF_DIGEST = "MD5"

/**
 * Conforming with CryptoJS AES method
 */
@InternalParsersApi
public class CryptoAES(
	private val context: MangaLoaderContext,
) {

	/**
	 * Decrypt using CryptoJS defaults compatible method.
	 * Uses KDF equivalent to OpenSSL's EVP_BytesToKey function
	 *
	 * http://stackoverflow.com/a/29152379/4405051
	 * @param cipherText base64 encoded ciphertext
	 * @param password passphrase
	 */
	@Throws(Exception::class)
	public fun decrypt(cipherText: String, password: String): String {
		val ctBytes = context.decodeBase64(cipherText)
		val saltBytes = ctBytes.copyOfRange(8, 16)
		val cipherTextBytes = ctBytes.copyOfRange(16, ctBytes.size)
		val md5: MessageDigest = MessageDigest.getInstance(KDF_DIGEST)
		val keyAndIV = generateKeyAndIV(32, 16, 1, saltBytes, password.toByteArray(Charsets.UTF_8), md5)
		return decryptAES(
			cipherTextBytes,
			keyAndIV.getOrNull(0) ?: ByteArray(32),
			keyAndIV.getOrNull(1) ?: ByteArray(16),
		)
	}

	/**
	 * Decrypt using CryptoJS defaults compatible method.
	 *
	 * @param cipherText base64 encoded ciphertext
	 * @param keyBytes key as a bytearray
	 * @param ivBytes iv as a bytearray
	 */
	@Throws(Exception::class)
	public fun decrypt(cipherText: String, keyBytes: ByteArray, ivBytes: ByteArray): String {
		val cipherTextBytes = context.decodeBase64(cipherText)
		return decryptAES(cipherTextBytes, keyBytes, ivBytes)
	}

	/**
	 * Decrypt using CryptoJS defaults compatible method.
	 *
	 * @param cipherTextBytes encrypted text as a bytearray
	 * @param keyBytes key as a bytearray
	 * @param ivBytes iv as a bytearray
	 */
	@Throws(Exception::class)
	private fun decryptAES(cipherTextBytes: ByteArray, keyBytes: ByteArray, ivBytes: ByteArray): String {
		val cipher = Cipher.getInstance(HASH_CIPHER)
		val keyS = SecretKeySpec(keyBytes, AES)
		cipher.init(Cipher.DECRYPT_MODE, keyS, IvParameterSpec(ivBytes))
		return cipher.doFinal(cipherTextBytes).toString(Charsets.UTF_8)
	}

	/**
	 * Generates a key and an initialization vector (IV) with the given salt and password.
	 *
	 * https://stackoverflow.com/a/41434590
	 * This method is equivalent to OpenSSL's EVP_BytesToKey function
	 * (see https://github.com/openssl/openssl/blob/master/crypto/evp/evp_key.c).
	 * By default, OpenSSL uses a single iteration, MD5 as the algorithm and UTF-8 encoded password data.
	 *
	 * @param keyLength the length of the generated key (in bytes)
	 * @param ivLength the length of the generated IV (in bytes)
	 * @param iterations the number of digestion rounds
	 * @param salt the salt data (8 bytes of data or `null`)
	 * @param password the password data (optional)
	 * @param md the message digest algorithm to use
	 * @return an two-element array with the generated key and IV
	 */
	@Suppress("SameParameterValue")
	@Throws(Exception::class)
	private fun generateKeyAndIV(
		keyLength: Int,
		ivLength: Int,
		iterations: Int,
		salt: ByteArray?,
		password: ByteArray,
		md: MessageDigest,
	): Array<ByteArray?> {
		val digestLength = md.digestLength
		val requiredLength = (keyLength + ivLength + digestLength - 1) / digestLength * digestLength
		val generatedData = ByteArray(requiredLength)
		var generatedLength = 0
		md.reset()

		// Repeat process until sufficient data has been generated
		while (generatedLength < keyLength + ivLength) {

			// Digest data (last digest if available, password data, salt if available)
			if (generatedLength > 0) md.update(generatedData, generatedLength - digestLength, digestLength)
			md.update(password)
			if (salt != null) md.update(salt, 0, 8)
			md.digest(generatedData, generatedLength, digestLength)

			// additional rounds
			for (i in 1 until iterations) {
				md.update(generatedData, generatedLength, digestLength)
				md.digest(generatedData, generatedLength, digestLength)
			}
			generatedLength += digestLength
		}

		// Copy key and IV into separate byte arrays
		val result = arrayOfNulls<ByteArray>(2)
		result[0] = generatedData.copyOfRange(0, keyLength)
		if (ivLength > 0) result[1] = generatedData.copyOfRange(keyLength, keyLength + ivLength)
		return result
	}
}
