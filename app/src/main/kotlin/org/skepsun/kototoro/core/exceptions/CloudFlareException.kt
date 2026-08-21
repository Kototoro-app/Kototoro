package org.skepsun.kototoro.core.exceptions

import okio.IOException
import org.skepsun.kototoro.parsers.model.ContentSource

abstract class CloudFlareException(
    message: String,
    val state: Int,
) : IOException(message) {

    abstract val url: String

    abstract val source: ContentSource
}
