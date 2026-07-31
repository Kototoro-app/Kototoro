package org.skepsun.kototoro.core.image

import java.io.IOException

class ImageDecodeException(
	val uri: String?,
	val format: String?,
	message: String? = null,
	cause: Throwable? = null,
) : IOException(message ?: "Failed to decode image", cause)
