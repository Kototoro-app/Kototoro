package org.skepsun.kototoro.core.db

import android.content.Context
import androidx.room.Database
import androidx.room.InvalidationTracker
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
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
import org.skepsun.kototoro.core.db.entity.MangaTagsEntity
import org.skepsun.kototoro.core.db.entity.TagEntity
import org.skepsun.kototoro.core.db.entity.TrackingSiteItemEntity
import org.skepsun.kototoro.core.db.entity.TrackingSiteLinkEntity
import org.skepsun.kototoro.core.db.migrations.Migration10To11
import org.skepsun.kototoro.core.db.migrations.Migration11To12
import org.skepsun.kototoro.core.db.migrations.Migration12To13
import org.skepsun.kototoro.core.db.migrations.Migration13To14
import org.skepsun.kototoro.core.db.migrations.Migration14To15
import org.skepsun.kototoro.core.db.migrations.Migration15To16
import org.skepsun.kototoro.core.db.migrations.Migration16To17
import org.skepsun.kototoro.core.db.migrations.Migration17To18
import org.skepsun.kototoro.core.db.migrations.Migration18To19
import org.skepsun.kototoro.core.db.migrations.Migration19To20
import org.skepsun.kototoro.core.db.migrations.Migration34To35
import org.skepsun.kototoro.core.db.migrations.Migration37To38
import org.skepsun.kototoro.core.db.migrations.Migration38To39
import org.skepsun.kototoro.core.db.migrations.Migration39To40
import org.skepsun.kototoro.core.db.migrations.Migration40To41
import org.skepsun.kototoro.core.db.migrations.Migration41To42
import org.skepsun.kototoro.core.db.migrations.Migration42To43
import org.skepsun.kototoro.core.db.migrations.Migration43To44
import org.skepsun.kototoro.core.db.migrations.Migration44To45
import org.skepsun.kototoro.core.db.migrations.Migration45To46
import org.skepsun.kototoro.core.db.migrations.Migration46To47
import org.skepsun.kototoro.core.db.migrations.Migration47To48
import org.skepsun.kototoro.core.db.migrations.Migration48To49
import org.skepsun.kototoro.core.db.migrations.Migration54To55
import org.skepsun.kototoro.core.db.migrations.Migration55To56
import org.skepsun.kototoro.core.db.migrations.Migration56To57
import org.skepsun.kototoro.core.db.migrations.Migration57To58
import org.skepsun.kototoro.core.db.migrations.Migration58To59
import org.skepsun.kototoro.core.db.migrations.Migration59To60
import org.skepsun.kototoro.core.db.migrations.Migration60To61
import org.skepsun.kototoro.core.db.migrations.Migration61To62
import org.skepsun.kototoro.core.db.migrations.Migration62To63
import org.skepsun.kototoro.core.db.migrations.Migration63To64
import org.skepsun.kototoro.core.db.migrations.Migration64To65
import org.skepsun.kototoro.core.db.migrations.Migration65To66
import org.skepsun.kototoro.core.db.migrations.Migration66To67
import org.skepsun.kototoro.core.db.migrations.Migration67To68
import org.skepsun.kototoro.core.db.migrations.Migration68To69
import org.skepsun.kototoro.core.db.migrations.Migration69To70
import org.skepsun.kototoro.core.db.migrations.Migration70To71
import org.skepsun.kototoro.core.db.migrations.Migration71To72
import org.skepsun.kototoro.core.db.migrations.Migration72To73
import org.skepsun.kototoro.core.db.migrations.Migration73To74
import org.skepsun.kototoro.core.db.migrations.Migration74To75
import org.skepsun.kototoro.core.db.migrations.Migration75To76
import org.skepsun.kototoro.core.db.migrations.Migration1To2
import org.skepsun.kototoro.core.db.migrations.Migration20To21
import org.skepsun.kototoro.core.db.migrations.Migration21To22
import org.skepsun.kototoro.core.db.migrations.Migration22To23
import org.skepsun.kototoro.core.db.migrations.Migration23To24
import org.skepsun.kototoro.core.db.migrations.Migration24To23
import org.skepsun.kototoro.core.db.migrations.Migration24To25
import org.skepsun.kototoro.core.db.migrations.Migration25To26
import org.skepsun.kototoro.core.db.migrations.Migration26To27
import org.skepsun.kototoro.core.db.migrations.Migration27To28
import org.skepsun.kototoro.core.db.migrations.Migration28To29
import org.skepsun.kototoro.core.db.migrations.Migration29To30
import org.skepsun.kototoro.core.db.migrations.Migration30To31
import org.skepsun.kototoro.core.db.migrations.Migration31To32
import org.skepsun.kototoro.core.db.migrations.Migration32To33
import org.skepsun.kototoro.core.db.migrations.Migration33To34
import org.skepsun.kototoro.core.db.migrations.Migration2To3
import org.skepsun.kototoro.core.db.migrations.Migration3To4
import org.skepsun.kototoro.core.db.migrations.Migration4To5
import org.skepsun.kototoro.core.db.migrations.Migration5To6
import org.skepsun.kototoro.core.db.migrations.Migration6To7
import org.skepsun.kototoro.core.db.migrations.Migration7To8
import org.skepsun.kototoro.core.db.migrations.Migration8To9
import org.skepsun.kototoro.core.db.migrations.Migration9To10
import org.skepsun.kototoro.core.util.ext.processLifecycleScope
import org.skepsun.kototoro.entitygraph.data.EntityBindingRecord
import org.skepsun.kototoro.entitygraph.data.EntityGraphDao
import org.skepsun.kototoro.entitygraph.data.EntityPrefsRecord
import org.skepsun.kototoro.entitygraph.data.EntityRecord
import org.skepsun.kototoro.entitygraph.data.RelationRecord
import org.skepsun.kototoro.favourites.data.FavouriteCategoriesDao
import org.skepsun.kototoro.favourites.data.FavouriteCategoryEntity
import org.skepsun.kototoro.favourites.data.FavouriteEntity
import org.skepsun.kototoro.favourites.data.FavouritesDao
import org.skepsun.kototoro.favourites.data.WorkFavouriteEntity
import org.skepsun.kototoro.favourites.data.WorkFavouritesDao
import org.skepsun.kototoro.history.data.HistoryDao
import org.skepsun.kototoro.history.data.HistoryEntity
import org.skepsun.kototoro.history.data.WorkHistoryDao
import org.skepsun.kototoro.history.data.WorkHistoryEntity
import org.skepsun.kototoro.local.data.index.LocalContentIndexDao
import org.skepsun.kototoro.local.data.index.LocalContentIndexEntity
import org.skepsun.kototoro.scrobbling.common.data.ScrobblingDao
import org.skepsun.kototoro.scrobbling.common.data.ScrobblingEntity
import org.skepsun.kototoro.readingrecord.data.ReadingJumpPointEntity
import org.skepsun.kototoro.readingrecord.data.ReadingRecordDao
import org.skepsun.kototoro.readingrecord.data.ReadingRecordEntity
import org.skepsun.kototoro.stats.data.StatsDao
import org.skepsun.kototoro.stats.data.StatsEntity
import org.skepsun.kototoro.stats.data.WorkStatsDao
import org.skepsun.kototoro.stats.data.WorkStatsEntity
import org.skepsun.kototoro.suggestions.data.SuggestionDao
import org.skepsun.kototoro.suggestions.data.SuggestionEntity
import org.skepsun.kototoro.tracker.data.TrackEntity
import org.skepsun.kototoro.tracker.data.TrackLogEntity
import org.skepsun.kototoro.tracker.data.TracksDao
import org.skepsun.kototoro.work.data.WorkMigrationLedgerDao
import org.skepsun.kototoro.work.data.WorkMigrationLedgerEntity
import org.skepsun.kototoro.space.data.SpaceNavigationEntryEntity
import org.skepsun.kototoro.space.data.SpaceSessionDao
import org.skepsun.kototoro.space.data.SpaceSessionEntity
import org.skepsun.kototoro.space.data.SpaceRoutePreferencesDao
import org.skepsun.kototoro.space.data.SpaceRoutePreferencesEntity
import org.skepsun.kototoro.space.data.SpaceDefinitionDao
import org.skepsun.kototoro.space.data.SpaceDefinitionEntity

import org.skepsun.kototoro.explore.data.SourcePresetEntity
import org.skepsun.kototoro.explore.data.SourcePresetsDao

const val DATABASE_VERSION = 76

@Database(
	entities = [
		MangaEntity::class, TagEntity::class, HistoryEntity::class, WorkHistoryEntity::class, MangaTagsEntity::class, ChapterEntity::class,
		FavouriteCategoryEntity::class, FavouriteEntity::class, WorkFavouriteEntity::class, MangaPrefsEntity::class, TrackEntity::class,
		TrackLogEntity::class, SuggestionEntity::class, BookmarkEntity::class, ScrobblingEntity::class,
		MangaSourceEntity::class, StatsEntity::class, WorkStatsEntity::class, LocalContentIndexEntity::class, EpubChapterMappingEntity::class,
		JsonSourceEntity::class, ExternalExtensionRepoEntity::class,
		TrackingSiteItemEntity::class, TrackingSiteLinkEntity::class, SourcePresetEntity::class,
		EntityRecord::class, EntityBindingRecord::class, RelationRecord::class, EntityPrefsRecord::class,
		WorkMigrationLedgerEntity::class,
		ReadingRecordEntity::class, ReadingJumpPointEntity::class,
		SpaceSessionEntity::class, SpaceNavigationEntryEntity::class, SpaceRoutePreferencesEntity::class,
		SpaceDefinitionEntity::class,
		// EpubChapterEntity::class,
	],
	version = DATABASE_VERSION,
)
abstract class MangaDatabase : RoomDatabase() {

	abstract fun getHistoryDao(): HistoryDao

	abstract fun getWorkHistoryDao(): WorkHistoryDao

	abstract fun getTagsDao(): TagsDao

	abstract fun getMangaDao(): MangaDao

	abstract fun getFavouritesDao(): FavouritesDao

	abstract fun getWorkFavouritesDao(): WorkFavouritesDao

	abstract fun getPreferencesDao(): PreferencesDao

	abstract fun getFavouriteCategoriesDao(): FavouriteCategoriesDao

	abstract fun getTracksDao(): TracksDao

	abstract fun getTrackLogsDao(): TrackLogsDao

	abstract fun getSuggestionDao(): SuggestionDao

	abstract fun getBookmarksDao(): BookmarksDao

	abstract fun getScrobblingDao(): ScrobblingDao

	abstract fun getSourcesDao(): MangaSourcesDao

	abstract fun getStatsDao(): StatsDao

	abstract fun getWorkStatsDao(): WorkStatsDao

	abstract fun getLocalContentIndexDao(): LocalContentIndexDao

	abstract fun getChaptersDao(): ChaptersDao

	abstract fun getEpubChapterMappingDao(): EpubChapterMappingDao

	abstract fun getJsonSourceDao(): JsonSourceDao

	abstract fun getExternalExtensionRepoDao(): ExternalExtensionRepoDao

	abstract fun getTrackingSiteDao(): TrackingSiteDao

	abstract fun getSourcePresetsDao(): SourcePresetsDao

	abstract fun getEntityGraphDao(): EntityGraphDao

	abstract fun getWorkMigrationLedgerDao(): WorkMigrationLedgerDao

	abstract fun getReadingRecordDao(): ReadingRecordDao

	abstract fun getSpaceSessionDao(): SpaceSessionDao

	abstract fun getSpaceRoutePreferencesDao(): SpaceRoutePreferencesDao

	abstract fun getSpaceDefinitionDao(): SpaceDefinitionDao

	// abstract fun getEpubChapterDao(): EpubChapterDao
}

fun getDatabaseMigrations(context: Context): Array<Migration> = arrayOf(
	Migration1To2(),
	Migration2To3(),
	Migration3To4(),
	Migration4To5(),
	Migration5To6(),
	Migration6To7(),
	Migration7To8(),
	Migration8To9(),
	Migration9To10(),
	Migration10To11(),
	Migration11To12(),
	Migration12To13(),
	Migration13To14(),
	Migration14To15(),
	Migration15To16(),
	Migration16To17(context),
	Migration17To18(),
	Migration18To19(),
	Migration19To20(),
	Migration20To21(),
	Migration21To22(),
	Migration22To23(),
	Migration23To24(),
	Migration24To23(),
	Migration24To25(),
	Migration25To26(),
	Migration26To27(),
	Migration27To28(),
	Migration28To29(),
	Migration29To30(),
	Migration30To31(),
	Migration31To32(),
	Migration32To33(),
	Migration33To34(),
	Migration34To35(),
	org.skepsun.kototoro.core.db.migrations.Migration35To36(),
	org.skepsun.kototoro.core.db.migrations.Migration36To37(),
	Migration37To38(),
	Migration38To39(),
	Migration39To40(),
	Migration40To41(),
	Migration41To42(),
	Migration42To43(),
	Migration43To44(),
	Migration44To45(),
	Migration45To46(),
	Migration46To47(),
	Migration47To48(),
	Migration48To49(),
	org.skepsun.kototoro.core.db.migrations.Migration49To50(),
	org.skepsun.kototoro.core.db.migrations.Migration50To51(),
	org.skepsun.kototoro.core.db.migrations.Migration51To52(),
	org.skepsun.kototoro.core.db.migrations.Migration52To53(),
	org.skepsun.kototoro.core.db.migrations.Migration53To54(),
	org.skepsun.kototoro.core.db.migrations.Migration54To55(),
	org.skepsun.kototoro.core.db.migrations.Migration55To56(),
	org.skepsun.kototoro.core.db.migrations.Migration56To57(),
	org.skepsun.kototoro.core.db.migrations.Migration57To58(),
	org.skepsun.kototoro.core.db.migrations.Migration58To59(),
	org.skepsun.kototoro.core.db.migrations.Migration59To60(),
	Migration60To61(),
	Migration61To62(),
	Migration62To63(),
	Migration63To64(),
	Migration64To65(),
	Migration65To66(),
	Migration66To67(),
	Migration67To68(),
	Migration68To69(),
	Migration69To70(),
	Migration70To71(),
	Migration71To72(),
	Migration72To73(),
	Migration73To74(),
	Migration74To75(),
	Migration75To76(),
)

fun MangaDatabase(context: Context): MangaDatabase = Room
	.databaseBuilder(context, MangaDatabase::class.java, "kototoro-db")
	.addMigrations(*getDatabaseMigrations(context))
	.fallbackToDestructiveMigrationOnDowngrade()
	.addCallback(DatabasePrePopulateCallback(context.resources))
	.build()

fun InvalidationTracker.removeObserverAsync(observer: InvalidationTracker.Observer) {
	val scope = processLifecycleScope
	if (scope.isActive) {
		processLifecycleScope.launch(Dispatchers.Default, CoroutineStart.ATOMIC) {
			removeObserver(observer)
		}
	}
}
