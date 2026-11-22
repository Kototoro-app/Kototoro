package org.skepsun.kototoro.community.domain

/**
 * Which sources a rating or comment may name, and how a reader's own sources are matched against the ones a work's
 * readers named (community plan §10.3).
 *
 * Only sources every reader can have under the same id are published: extensions and built-in parsers. Local imports
 * and user-configured sources (TVBox, Legado, LNReader, JS rules: every `JSON_` source) are never named, since their
 * ids come from the reader's own configuration links. Adult sources only with [allowAdult]: the reader turned on
 * ratings of adult works, which already publishes what they read there.
 */
object CommunitySources {

    /** At most this many sources per work in the snapshot. */
    const val MAX_PER_WORK = 8

    fun publishable(sourceName: String, contentType: String, allowAdult: Boolean = false): Boolean =
        sourceName.isNotBlank() && !sourceName.startsWith("JSON_") && !sourceName.startsWith("LOCAL") &&
            (allowAdult || !isAdult(contentType))

    fun isAdult(contentType: String): Boolean = contentType.startsWith("HENTAI_")

    /** The site's host from an absolute address, without `www.`; null for a relative or malformed one. */
    fun hostOf(url: String?): String? {
        val value = url?.trim().orEmpty()
        val start = value.indexOf("://").takeIf { it > 0 }?.plus(3) ?: return null
        val end = value.indexOfAny(charArrayOf('/', '?', '#', ':'), start).takeIf { it > 0 } ?: value.length
        return value.substring(start, end).lowercase().removePrefix("www.").takeIf { it.contains('.') }
    }
}
