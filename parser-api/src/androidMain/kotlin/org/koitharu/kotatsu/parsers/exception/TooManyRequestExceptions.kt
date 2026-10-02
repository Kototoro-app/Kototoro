package org.koitharu.kotatsu.parsers.exception

import okio.IOException
import java.time.Instant
import java.time.temporal.ChronoUnit

public class TooManyRequestExceptions(
	public val url: String,
	retryAfter: Long,
) : IOException("Too man requests") {

	public val retryAt: Instant? = if (retryAfter > 0 && retryAfter < Long.MAX_VALUE) {
		Instant.now().plusMillis(retryAfter)
	} else {
		null
	}

	public fun getRetryDelay(): Long {
		if (retryAt == null) {
			return -1L
		}
		return Instant.now().until(retryAt, ChronoUnit.MILLIS).coerceAtLeast(0L)
	}

	override val message: String?
		get() = if (retryAt != null) {
			"${super.message}, retry at $retryAt"
		} else {
			super.message
		}
}
