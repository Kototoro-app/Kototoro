package org.koitharu.kotatsu.parsers.model

import okhttp3.HttpUrl.Companion.toHttpUrl

public data class Favicon(
	@JvmField public val url: String,
	@JvmField public val size: Int,
	@JvmField internal val rel: String?,
) : Comparable<Favicon> {

	@JvmField
	public val type: String = url.toHttpUrl().pathSegments.last()
		.substringAfterLast('.', "").lowercase()

	override fun compareTo(other: Favicon): Int {
		val res = size.compareTo(other.size)
		if (res != 0) {
			return res
		}
		return relWeightOf(rel).compareTo(relWeightOf(other.rel))
	}

	private fun relWeightOf(rel: String?) = when (rel) {
		"apple-touch-icon" -> 1 // Prefer apple-touch-icon because it has a better quality
		"mask-icon" -> -1
		else -> 0
	}
}
