package org.skepsun.kototoro.core.parser

import org.skepsun.kototoro.core.model.ContentSourceInfo
import org.skepsun.kototoro.parsers.model.ContentSource
import javax.inject.Inject

class ContentSourceInfoResolver @Inject constructor() : ContentSourceResolver {
    override fun supports(source: ContentSource): Boolean = source is ContentSourceInfo

    override fun resolve(source: ContentSource): ContentSource? {
        return (source as? ContentSourceInfo)?.mangaSource
    }
}
