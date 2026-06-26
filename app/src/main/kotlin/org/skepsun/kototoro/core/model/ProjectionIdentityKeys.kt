package org.skepsun.kototoro.core.model

object ProjectionIdentityKeys {

	fun bindingKey(url: String, publicUrl: String): String? {
		url.trim().takeIf { it.isNotEmpty() }?.let { return "url:$it" }
		publicUrl.trim().takeIf { it.isNotEmpty() }?.let { return "public_url:$it" }
		return null
	}

	fun contentCompactKey(
		source: String,
		id: Long,
		url: String,
		publicUrl: String,
	): String {
		return bindingKey(url, publicUrl)?.let { "projection:$source:$it" }
			?: "projection-id:$id"
	}

	fun hasSameIdentity(
		source: String,
		url: String,
		publicUrl: String,
		otherSource: String,
		otherUrl: String,
		otherPublicUrl: String,
	): Boolean {
		val hasSameUrl = url.isNotBlank() && url == otherUrl
		val hasSamePublicUrl = publicUrl.isNotBlank() && publicUrl == otherPublicUrl
		return source == otherSource && (hasSameUrl || hasSamePublicUrl)
	}
}
