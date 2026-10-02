package org.skepsun.kototoro.core.db

import androidx.room.ConstructedBy
import androidx.room.Database
import androidx.room.RoomDatabaseConstructor
import androidx.room.RoomDatabase
import org.skepsun.kototoro.bookmarks.data.BookmarkEntity
import org.skepsun.kototoro.bookmarks.data.BookmarksDao
import org.skepsun.kototoro.core.db.dao.ChaptersDao
// import org.skepsun.kototoro.core.db.dao.EpubChapterDao
import org.skepsun.kototoro.core.db.dao.EpubChapterMappingDao
import org.skepsun.kototoro.core.db.dao.ExternalExtensionRepoDao
import org.skepsun.kototoro.core.db.dao.JsonSourceDao
import org.skepsun.kototoro.core.db.dao.MangaDao
import org.skepsun.kototoro.core.db.dao.MangaSourcesDao
import org.skepsun.kototoro.core.db.dao.PreferencesDao
import org.skepsun.kototoro.core.db.dao.SourceOriginsDao
import org.skepsun.kototoro.core.db.dao.SourceRefreshStateDao
import org.skepsun.kototoro.core.db.dao.TagsDao
import org.skepsun.kototoro.core.db.dao.TrackLogsDao
import org.skepsun.kototoro.core.db.dao.TrackingSiteDao
import org.skepsun.kototoro.core.db.entity.ChapterEntity
// import org.skepsun.kototoro.core.db.entity.EpubChapterEntity
import org.skepsun.kototoro.core.db.entity.EpubChapterMappingEntity
import org.skepsun.kototoro.core.db.entity.ExternalExtensionRepoEntity
import org.skepsun.kototoro.core.db.entity.JsonSourceEntity
import org.skepsun.kototoro.core.db.entity.MangaEntity
import org.skepsun.kototoro.core.db.entity.MangaPrefsEntity
import org.skepsun.kototoro.core.db.entity.MangaSourceEntity
import org.skepsun.kototoro.core.db.entity.RestoreCheckpointDao
import org.skepsun.kototoro.core.db.entity.RestoreCheckpointEntity
import org.skepsun.kototoro.core.db.entity.MangaTagsEntity
import org.skepsun.kototoro.core.db.entity.SourceOriginEntity
import org.skepsun.kototoro.core.db.entity.SourceRefreshStateEntity
import org.skepsun.kototoro.core.db.entity.TagEntity
import org.skepsun.kototoro.core.db.entity.TrackingSiteItemEntity
import org.skepsun.kototoro.core.db.entity.TrackingSiteLinkEntity
import org.skepsun.kototoro.notes.data.MediaNoteDao
import org.skepsun.kototoro.notes.data.MediaNoteEntity
import org.skepsun.kototoro.favourites.data.FavouriteCategoriesDao
import org.skepsun.kototoro.favourites.data.FavouriteCategoryEntity
import org.skepsun.kototoro.favourites.data.FavouriteEntity
import org.skepsun.kototoro.favourites.data.FavouritesDao
import org.skepsun.kototoro.history.data.HistoryDao
import org.skepsun.kototoro.history.data.HistoryEntity
import org.skepsun.kototoro.local.data.index.LocalContentIndexDao
import org.skepsun.kototoro.local.data.index.LocalContentIndexEntity
import org.skepsun.kototoro.scrobbling.common.data.ScrobblingDao
import org.skepsun.kototoro.scrobbling.common.data.ScrobblingEntity
import org.skepsun.kototoro.readingrecord.data.ReadingJumpPointEntity
import org.skepsun.kototoro.readingrecord.data.ReadingRecordDao
import org.skepsun.kototoro.readingrecord.data.ReadingRecordEntity
import org.skepsun.kototoro.stats.data.StatsDao
import org.skepsun.kototoro.stats.data.StatsEntity
import org.skepsun.kototoro.suggestions.data.SuggestionDao
import org.skepsun.kototoro.suggestions.data.SuggestionEntity
import org.skepsun.kototoro.tracker.data.TrackEntity
import org.skepsun.kototoro.tracker.data.TrackLogEntity
import org.skepsun.kototoro.tracker.data.TracksDao
import org.skepsun.kototoro.space.data.SpaceNavigationEntryEntity
import org.skepsun.kototoro.space.data.SpaceSessionDao
import org.skepsun.kototoro.space.data.SpaceSessionEntity
import org.skepsun.kototoro.space.data.SpaceRoutePreferencesDao
import org.skepsun.kototoro.space.data.SpaceRoutePreferencesEntity
import org.skepsun.kototoro.space.data.SpaceDefinitionDao
import org.skepsun.kototoro.space.data.SpaceDefinitionEntity

import org.skepsun.kototoro.explore.data.SourcePresetEntity
import org.skepsun.kototoro.explore.data.SourcePresetsDao
import org.skepsun.kototoro.core.replace.ReplaceRule
import org.skepsun.kototoro.core.replace.ReplaceRuleDao
import org.skepsun.kototoro.reader.novel.annotation.NovelMarkingDao
import org.skepsun.kototoro.reader.novel.annotation.NovelMarkingEntity
import org.skepsun.kototoro.core.dictionary.DictionaryRule
import org.skepsun.kototoro.core.dictionary.DictionaryRuleDao
import org.skepsun.kototoro.core.dictionary.TranslationDictionaryDao
import org.skepsun.kototoro.core.dictionary.TranslationDictionaryEntity

const val DATABASE_VERSION = 84

@Database(
    entities = [
        MangaEntity::class, TagEntity::class, HistoryEntity::class, MangaTagsEntity::class, ChapterEntity::class,
        FavouriteCategoryEntity::class, FavouriteEntity::class, MangaPrefsEntity::class, TrackEntity::class,
        TrackLogEntity::class, SuggestionEntity::class, BookmarkEntity::class, ScrobblingEntity::class,
        MangaSourceEntity::class, StatsEntity::class, LocalContentIndexEntity::class, EpubChapterMappingEntity::class,
        JsonSourceEntity::class, ExternalExtensionRepoEntity::class,
        TrackingSiteItemEntity::class, TrackingSiteLinkEntity::class, SourcePresetEntity::class,
        ReadingRecordEntity::class, ReadingJumpPointEntity::class, RestoreCheckpointEntity::class,
        SpaceSessionEntity::class, SpaceNavigationEntryEntity::class, SpaceRoutePreferencesEntity::class,
        SpaceDefinitionEntity::class,
        SourceOriginEntity::class, SourceRefreshStateEntity::class,
        ReplaceRule::class,
        NovelMarkingEntity::class,
        MediaNoteEntity::class,
        DictionaryRule::class,
        TranslationDictionaryEntity::class,
        // EpubChapterEntity::class,
    ],
    version = DATABASE_VERSION,
)
@ConstructedBy(MangaDatabaseConstructor::class)
abstract class MangaDatabase : RoomDatabase() {

    abstract fun getHistoryDao(): HistoryDao

    abstract fun getHistoryLibraryReadDao(): org.skepsun.kototoro.history.data.HistoryLibraryReadDao

    abstract fun getTagsDao(): TagsDao

    abstract fun getMangaDao(): MangaDao

    abstract fun getFavouritesDao(): FavouritesDao

    abstract fun getFavouriteLibraryReadDao(): org.skepsun.kototoro.favourites.data.FavouriteLibraryReadDao

    abstract fun getPreferencesDao(): PreferencesDao

    abstract fun getFavouriteCategoriesDao(): FavouriteCategoriesDao

    abstract fun getTracksDao(): TracksDao

    abstract fun getTrackerReadDao(): org.skepsun.kototoro.tracker.data.TrackerReadDao

    abstract fun getTrackLogsDao(): TrackLogsDao

    abstract fun getSuggestionDao(): SuggestionDao

    abstract fun getBookmarksDao(): BookmarksDao

    abstract fun getScrobblingDao(): ScrobblingDao

    abstract fun getSourcesDao(): MangaSourcesDao

    abstract fun getStatsDao(): StatsDao

    abstract fun getLocalContentIndexDao(): LocalContentIndexDao

    abstract fun getChaptersDao(): ChaptersDao

    abstract fun getEpubChapterMappingDao(): EpubChapterMappingDao

    abstract fun getJsonSourceDao(): JsonSourceDao

    abstract fun getExternalExtensionRepoDao(): ExternalExtensionRepoDao

    abstract fun getTrackingSiteDao(): TrackingSiteDao

    abstract fun getSourcePresetsDao(): SourcePresetsDao

    abstract fun getReadingRecordDao(): ReadingRecordDao

    abstract fun getSpaceSessionDao(): SpaceSessionDao

    abstract fun getSpaceRoutePreferencesDao(): SpaceRoutePreferencesDao

    abstract fun getSpaceDefinitionDao(): SpaceDefinitionDao

    abstract fun getRestoreCheckpointDao(): RestoreCheckpointDao

    abstract fun getSourceOriginsDao(): SourceOriginsDao

    abstract fun getSourceRefreshStateDao(): SourceRefreshStateDao

    abstract fun getReplaceRuleDao(): ReplaceRuleDao

    abstract fun getNovelMarkingDao(): NovelMarkingDao

    abstract fun getMediaNoteDao(): MediaNoteDao

    abstract fun getMigrationDao(): org.skepsun.kototoro.migration.data.MigrationDao

    abstract fun getDictionaryRuleDao(): DictionaryRuleDao

    abstract fun getTranslationDictionaryDao(): TranslationDictionaryDao

    // abstract fun getEpubChapterDao(): EpubChapterDao
}

// Room generates the `actual` implementation for every target.
@Suppress("KotlinNoActualForExpect")
expect object MangaDatabaseConstructor : RoomDatabaseConstructor<MangaDatabase> {
    override fun initialize(): MangaDatabase
}
