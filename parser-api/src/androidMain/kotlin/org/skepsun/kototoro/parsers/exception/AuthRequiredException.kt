package org.skepsun.kototoro.parsers.exception

import okio.IOException
import org.skepsun.kototoro.parsers.InternalParsersApi
import org.skepsun.kototoro.parsers.model.ContentSource

/**
 * Authorization is required for access to the requested content
 */
public class AuthRequiredException @InternalParsersApi @JvmOverloads constructor(
	public val source: ContentSource,
	cause: Throwable? = null,
) : IOException("Authorization required", cause)
