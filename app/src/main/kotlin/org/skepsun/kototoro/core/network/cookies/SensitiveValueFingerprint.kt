package org.skepsun.kototoro.core.network.cookies

import java.security.MessageDigest

internal fun sensitiveValueFingerprint(value: String?): String {
	if (value.isNullOrEmpty()) return "<empty>"
	val digest = MessageDigest.getInstance("SHA-256")
		.digest(value.toByteArray(Charsets.UTF_8))
		.take(FINGERPRINT_BYTES)
		.joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }
	return "sha256=$digest,length=${value.length}"
}

private const val FINGERPRINT_BYTES = 6
