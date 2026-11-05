package org.skepsun.kototoro.local.data

import javax.inject.Qualifier

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class PageCache

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class FaviconCache

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class NovelCache

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ChapterThumbnailCache
