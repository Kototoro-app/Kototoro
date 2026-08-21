package org.skepsun.kototoro.core.parser

import org.skepsun.kototoro.core.model.LocalMangaSource
import org.skepsun.kototoro.core.model.LocalNovelSource
import org.skepsun.kototoro.core.model.LocalVideoSource
import org.skepsun.kototoro.core.model.UnknownContentSource
import org.skepsun.kototoro.local.data.LocalMangaRepository
import org.skepsun.kototoro.local.novel.LocalNovelRepository
import org.skepsun.kototoro.parsers.model.ContentSource
import javax.inject.Inject

class BuiltinContentRepositoryProvider @Inject constructor(
    private val localMangaRepository: LocalMangaRepository,
    private val localNovelRepository: LocalNovelRepository,
) : ContentRepositoryProvider {

    override fun supports(source: ContentSource): Boolean {
        return source == LocalMangaSource || source == LocalNovelSource || source == LocalVideoSource || source == UnknownContentSource
    }

    override fun create(source: ContentSource): ContentRepository? {
        return when (source) {
            LocalMangaSource -> localMangaRepository
            LocalNovelSource -> localNovelRepository
            LocalVideoSource -> localMangaRepository
            UnknownContentSource -> EmptyContentRepository(source)
            else -> null
        }
    }
}
