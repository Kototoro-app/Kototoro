package org.skepsun.kototoro.local.epub

import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import org.skepsun.kototoro.core.db.MangaDatabase
import org.skepsun.kototoro.core.db.dao.EpubChapterMappingDao
import javax.inject.Singleton

/**
 * Dagger module for EPUB-related dependencies
 */
@Module
@InstallIn(SingletonComponent::class)
interface EpubModule {

    @Binds
    @Singleton
    fun bindEpubFileManager(impl: EpubFileManagerImpl): EpubFileManager

    @Binds
    @Singleton
    fun bindEpubDeletionManager(impl: EpubDeletionManagerImpl): EpubDeletionManager

    @Binds
    @Singleton
    fun bindChapterIdGenerator(impl: ChapterIdGeneratorImpl): ChapterIdGenerator

    @Binds
    @Singleton
    fun bindLegacyEpubMigration(impl: LegacyEpubMigrationImpl): LegacyEpubMigration

    companion object {
        @Provides
        @Singleton
        fun provideEpubChapterMappingDao(database: MangaDatabase): EpubChapterMappingDao {
            return database.getEpubChapterMappingDao()
        }

        @Provides
        @Singleton
        fun provideEpubContentCache(): EpubContentCache {
            return EpubContentCache.getInstance()
        }

        @Provides
        @Singleton
        fun provideEpubReader(cache: EpubContentCache): EpubReader {
            return EpubReaderImpl(cache)
        }
    }
}
