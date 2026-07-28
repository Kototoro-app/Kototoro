package org.skepsun.kototoro.core.parser

import org.skepsun.kototoro.core.cache.MemoryContentCache
import org.skepsun.kototoro.core.model.TestContentSource
import org.skepsun.kototoro.parsers.ContentLoaderContext

@Suppress("unused")
class TestContentRepository(
	private val loaderContext: ContentLoaderContext,
	cache: MemoryContentCache
) : EmptyContentRepository(TestContentSource)
