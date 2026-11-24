package org.skepsun.kototoro.core.parser

import org.skepsun.kototoro.parsers.model.ContentSource

interface ContentSourceResolver {
    fun supports(source: ContentSource): Boolean = true
    fun resolve(source: ContentSource): ContentSource?
    fun cacheVersion(): Long = 0L
}
