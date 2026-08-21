package org.skepsun.kototoro.core.parser

import kotlinx.coroutines.runBlocking
import org.skepsun.kototoro.core.jsonsource.JsonContentSource
import org.skepsun.kototoro.core.jsonsource.JsonSourceManager
import org.skepsun.kototoro.parsers.model.ContentSource
import javax.inject.Inject

class JsonContentSourceResolver @Inject constructor(
    private val jsonSourceManager: JsonSourceManager,
) : ContentSourceResolver {

    override fun supports(source: ContentSource): Boolean {
        return source !is JsonContentSource && source.name.startsWith(JSON_PREFIX)
    }

    override fun resolve(source: ContentSource): ContentSource? {
        if (!supports(source)) {
            return null
        }
        return runBlocking {
            jsonSourceManager.getById(source.name)
        }?.let(::JsonContentSource)
    }

    private companion object {
        private const val JSON_PREFIX = "JSON_"
    }
}
