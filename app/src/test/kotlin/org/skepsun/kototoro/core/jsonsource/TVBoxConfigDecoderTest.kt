package org.skepsun.kototoro.core.jsonsource

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.nio.charset.StandardCharsets
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class TVBoxConfigDecoderTest {

	@Test
	fun `decodes marked base64 configuration`() {
		val json = """{"sites":[]}"""
		val wrapped = "config12**" + Base64.getEncoder().encodeToString(json.toByteArray())

		assertEquals(json, TVBoxConfigDecoder.decode(wrapped))
	}

	@Test
	fun `decodes tvbox aes cbc configuration`() {
		val json = """{"sites":[{"name":"肥猫"}]}"""
		val keySeed = "123456"
		val ivSeed = "iv-seed-12345"
		val key = keySeed.padEnd(16, '0')
		val iv = ivSeed.padEnd(16, '0')
		val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
		cipher.init(
			Cipher.ENCRYPT_MODE,
			SecretKeySpec(key.toByteArray(StandardCharsets.UTF_8), "AES"),
			IvParameterSpec(iv.toByteArray(StandardCharsets.UTF_8)),
		)
		val envelope = "$#$keySeed#$".toByteArray() + cipher.doFinal(json.toByteArray()) + ivSeed.toByteArray()
		val wrapped = envelope.joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

		assertEquals(json, TVBoxConfigDecoder.decode(wrapped))
	}
}
