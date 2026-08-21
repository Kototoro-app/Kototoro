package org.skepsun.kototoro.core.parser

import org.skepsun.kototoro.parsers.model.ContentSource

interface ContentRepositoryProvider {
    fun supports(source: ContentSource): Boolean = true
    fun create(source: ContentSource): ContentRepository?
}
