package org.skepsun.kototoro.sync.domain

import android.accounts.Account
import android.content.ContentProviderClient
import android.content.ContentProviderOperation
import android.content.ContentProviderResult
import android.content.Context
import android.content.OperationApplicationException
import android.content.SyncResult
import android.content.SyncStats
import android.database.Cursor
import android.util.Log
import androidx.annotation.WorkerThread
import androidx.core.net.toUri
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.IOException
import org.jetbrains.annotations.Blocking
import org.skepsun.kototoro.BuildConfig
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.db.TABLE_FAVOURITES
import org.skepsun.kototoro.core.db.TABLE_FAVOURITE_CATEGORIES
import org.skepsun.kototoro.core.db.TABLE_HISTORY
import org.skepsun.kototoro.core.db.TABLE_MANGA
import org.skepsun.kototoro.core.db.TABLE_MANGA_TAGS
import org.skepsun.kototoro.core.db.TABLE_TAGS
import org.skepsun.kototoro.core.db.TABLE_WORK_FAVOURITES
import org.skepsun.kototoro.core.db.TABLE_WORK_HISTORY
import org.skepsun.kototoro.core.db.MangaDatabase
import org.skepsun.kototoro.core.network.BaseHttpClient
import org.skepsun.kototoro.core.util.ext.buildContentValues
import org.skepsun.kototoro.core.util.ext.map
import org.skepsun.kototoro.core.util.ext.mapToSet
import org.skepsun.kototoro.core.util.ext.printStackTraceDebug
import org.skepsun.kototoro.favourites.data.WorkFavouriteEntity
import org.skepsun.kototoro.history.data.WorkHistoryEntity
import org.skepsun.kototoro.sync.data.SyncAuthApi
import org.skepsun.kototoro.sync.data.SyncAuthenticator
import org.skepsun.kototoro.sync.data.SyncInterceptor
import org.skepsun.kototoro.sync.data.SyncSettings
import org.skepsun.kototoro.sync.data.model.FavouriteCategorySyncDto
import org.skepsun.kototoro.sync.data.model.FavouriteSyncDto
import org.skepsun.kototoro.sync.data.model.HistorySyncDto
import org.skepsun.kototoro.sync.data.model.ContentSyncDto
import org.skepsun.kototoro.sync.data.model.ContentTagSyncDto
import org.skepsun.kototoro.sync.data.model.SyncDto
import org.skepsun.kototoro.work.domain.WorkResolver
import java.net.HttpURLConnection
import java.util.concurrent.TimeUnit

class SyncHelper @AssistedInject constructor(
	@ApplicationContext context: Context,
	@BaseHttpClient baseHttpClient: OkHttpClient,
	@Assisted private val account: Account,
	@Assisted private val provider: ContentProviderClient,
	private val settings: SyncSettings,
	private val db: MangaDatabase,
	private val workResolver: WorkResolver,
) {

	private val authorityHistory = context.getString(R.string.sync_authority_history)
	private val authorityFavourites = context.getString(R.string.sync_authority_favourites)
	private val mediaTypeJson = "application/json".toMediaType()
	private val httpClient = baseHttpClient.newBuilder()
		.authenticator(SyncAuthenticator(context, account, settings, SyncAuthApi(OkHttpClient())))
		.addInterceptor(SyncInterceptor(context, account))
		.build()
	private val baseUrl: String by lazy {
		settings.syncUrl
	}
	private val defaultGcPeriod: Long // gc period if sync enabled
		get() = TimeUnit.DAYS.toMillis(4)

	@WorkerThread
	fun syncFavourites(stats: SyncStats) {
		val payload = Json.encodeToString(
			SyncDto(
				history = null,
				favourites = getFavourites(),
				categories = getFavouriteCategories(),
				timestamp = System.currentTimeMillis(),
			),
		)
		val request = Request.Builder()
			.url("$baseUrl/resource/$TABLE_FAVOURITES")
			.post(payload.toRequestBody(mediaTypeJson))
			.build()
		val response = httpClient.newCall(request).execute().parseDtoOrNull()
		response?.categories?.let { categories ->
			val categoriesResult = upsertFavouriteCategories(categories)
			stats.numDeletes += categoriesResult.firstOrNull()?.count?.toLong() ?: 0L
			stats.numInserts += categoriesResult.drop(1).sumOf { it.count?.toLong() ?: 0L }
		}
		response?.favourites?.let { favourites ->
			val favouritesResult = upsertFavourites(favourites)
			stats.numDeletes += favouritesResult.firstOrNull()?.count?.toLong() ?: 0L
			stats.numInserts += favouritesResult.drop(1).sumOf { it.count?.toLong() ?: 0L }
			stats.numEntries += stats.numInserts + stats.numDeletes
		}
		gcFavourites()
	}

	@Blocking
	@WorkerThread
	fun syncHistory(stats: SyncStats) {
		val payload = Json.encodeToString(
			SyncDto(
				history = getHistory(),
				favourites = null,
				categories = null,
				timestamp = System.currentTimeMillis(),
			),
		)
		val request = Request.Builder()
			.url("$baseUrl/resource/$TABLE_HISTORY")
			.post(payload.toRequestBody(mediaTypeJson))
			.build()
		val response = httpClient.newCall(request).execute().parseDtoOrNull()
		response?.history?.let { history ->
			val result = upsertHistory(history)
			stats.numDeletes += result.firstOrNull()?.count?.toLong() ?: 0L
			stats.numInserts += result.drop(1).sumOf { it.count?.toLong() ?: 0L }
			stats.numEntries += stats.numInserts + stats.numDeletes
		}
		gcHistory()
	}

	fun onError(e: Throwable) {
		e.printStackTraceDebug()
	}

	fun onSyncComplete(result: SyncResult) {
		if (BuildConfig.DEBUG) {
			Log.i("Sync", "Sync finished: ${result.toDebugString()}")
		}
	}

	private fun upsertHistory(history: List<HistorySyncDto>): Array<ContentProviderResult> {
		val operations = ArrayList<ContentProviderOperation>()
		history.forEach {
			operations.addAll(upsertContent(it.manga, authorityHistory))
		}
		val result = if (operations.isEmpty()) emptyArray() else provider.applyBatch(operations)
		history.forEach { dto ->
			upsertWorkHistory(dto)
		}
		return result
	}

	private fun upsertFavouriteCategories(categories: List<FavouriteCategorySyncDto>): Array<ContentProviderResult> {
		val uri = uri(authorityFavourites, TABLE_FAVOURITE_CATEGORIES)
		val operations = ArrayList<ContentProviderOperation>()
		categories.mapTo(operations) {
			ContentProviderOperation.newInsert(uri)
				.withValues(it.toContentValues())
				.build()
		}
		return provider.applyBatch(operations)
	}

	private fun upsertFavourites(favourites: List<FavouriteSyncDto>): Array<ContentProviderResult> {
		val operations = ArrayList<ContentProviderOperation>()
		favourites.forEach {
			operations.addAll(upsertContent(it.manga, authorityFavourites))
		}
		val result = if (operations.isEmpty()) emptyArray() else provider.applyBatch(operations)
		favourites.forEach { dto ->
			upsertWorkFavourite(dto)
		}
		return result
	}

	private fun upsertContent(manga: ContentSyncDto, authority: String): List<ContentProviderOperation> {
		val tags = manga.tags
		val result = ArrayList<ContentProviderOperation>(tags.size * 2 + 1)
		for (tag in tags) {
			result += ContentProviderOperation.newInsert(uri(authority, TABLE_TAGS))
				.withValues(tag.toContentValues())
				.build()
			result += ContentProviderOperation.newInsert(uri(authority, TABLE_MANGA_TAGS))
				.withValues(
					buildContentValues(2) {
						put("manga_id", manga.id)
						put("tag_id", tag.id)
					},
				).build()
		}
		result.add(
			0,
			ContentProviderOperation.newInsert(uri(authority, TABLE_MANGA))
				.withValues(manga.toContentValues())
				.build(),
		)
		return result
	}

	private fun getHistory(): List<HistorySyncDto> {
		val workHistory = runBlocking { db.getWorkHistoryDao().dump().toList() }
		return workHistory.mapNotNull { entry: WorkHistoryEntity ->
			val mangaId = resolveSyncMangaIdForEntity(entry.entityId, entry.anchorMangaId) ?: return@mapNotNull null
			HistorySyncDto(
				entityId = entry.entityId,
				anchorMangaId = entry.anchorMangaId,
				mangaId = mangaId,
				createdAt = entry.createdAt,
				updatedAt = entry.updatedAt,
				chapterId = entry.chapterId,
				page = entry.page,
				scroll = entry.scroll,
				percent = entry.percent,
				deletedAt = entry.deletedAt,
				chaptersCount = entry.chaptersCount,
				manga = getContent(authorityHistory, mangaId),
			)
		}
	}

	private fun getFavourites(): List<FavouriteSyncDto> {
		val workFavourites = runBlocking { db.getWorkFavouritesDao().dump().toList() }
		return workFavourites.mapNotNull { entry: WorkFavouriteEntity ->
			val mangaId = resolveSyncMangaIdForEntity(entry.entityId) ?: return@mapNotNull null
			FavouriteSyncDto(
				entityId = entry.entityId,
				mangaId = mangaId,
				manga = getContent(authorityFavourites, mangaId),
				categoryId = entry.categoryId.toInt(),
				sortKey = entry.sortKey,
				pinned = entry.isPinned,
				createdAt = entry.createdAt,
				deletedAt = entry.deletedAt,
				updatedAt = entry.updatedAt,
			)
		}
	}

	private fun upsertWorkHistory(dto: HistorySyncDto) {
		val entityId = resolveSyncEntityId(dto.entityId, dto.mangaId) ?: return
		val anchorMangaId = dto.anchorMangaId?.takeIf(::mangaExists)
			?: resolveExistingLocalProjectionForEntity(entityId)
			?: dto.mangaId
		runBlocking {
			db.getWorkHistoryDao().upsert(
				WorkHistoryEntity(
					entityId = entityId,
					anchorMangaId = anchorMangaId,
					createdAt = dto.createdAt,
					updatedAt = dto.updatedAt,
					chapterId = dto.chapterId,
					page = dto.page,
					scroll = dto.scroll,
					percent = dto.percent,
					deletedAt = dto.deletedAt,
					chaptersCount = dto.chaptersCount,
					parentChapterId = null,
				),
			)
		}
	}

	private fun upsertWorkFavourite(dto: FavouriteSyncDto) {
		val entityId = resolveSyncEntityId(dto.entityId, dto.mangaId) ?: return
		runBlocking {
			db.getWorkFavouritesDao().upsert(
				WorkFavouriteEntity(
					entityId = entityId,
					categoryId = dto.categoryId.toLong(),
					anchorMangaId = dto.mangaId,
					sortKey = dto.sortKey,
					isPinned = dto.pinned,
					createdAt = dto.createdAt,
					deletedAt = dto.deletedAt,
					updatedAt = dto.updatedAt,
				),
			)
		}
	}

	private fun resolveSyncEntityId(remoteEntityId: Long?, mangaId: Long): Long? {
		resolveWorkEntityIdForLocalManga(mangaId)?.let { return it }
		if (remoteEntityId != null && remoteEntityId > 0L) {
			val identity = runBlocking { workResolver.resolveByEntityId(remoteEntityId) }
			return identity?.entityId?.takeIf { mangaId in identity.localMangaIds }
		}
		return null
	}

	private fun resolveSyncMangaIdForEntity(entityId: Long, fallbackMangaId: Long? = null): Long? {
		return resolveExistingLocalProjectionForEntity(entityId)
			?: fallbackMangaId?.takeIf(::mangaExists)
	}

	private fun resolveExistingLocalProjectionForEntity(entityId: Long): Long? {
		val identity = runBlocking { workResolver.resolveByEntityId(entityId) } ?: return null
		return identity.preferredMangaId?.takeIf(::mangaExists)
			?: identity.localMangaIds.firstOrNull(::mangaExists)
	}

	private fun mangaExists(mangaId: Long): Boolean {
		return runBlocking {
			db.getMangaDao().contains(mangaId)
		}
	}

	private fun resolveWorkEntityIdForLocalManga(mangaId: Long): Long? {
		return runBlocking {
			workResolver.resolveByMangaId(mangaId).entityId
		}
	}

	private fun getFavouriteCategories(): List<FavouriteCategorySyncDto> =
		provider.query(authorityFavourites, TABLE_FAVOURITE_CATEGORIES).map { cursor ->
			FavouriteCategorySyncDto(cursor)
		}

	private fun getContent(authority: String, id: Long): ContentSyncDto {
		val tags = requireNotNull(
			provider.query(
				uri(authority, TABLE_MANGA_TAGS),
				arrayOf("tag_id"),
				"manga_id = ?",
				arrayOf(id.toString()),
				null,
			)?.mapToSet {
				val tagId = it.getLong(it.getColumnIndexOrThrow("tag_id"))
				getTag(authority, tagId)
			},
		)
		return requireNotNull(
			provider.query(
				uri(authority, TABLE_MANGA),
				null,
				"manga_id = ?",
				arrayOf(id.toString()),
				null,
			)?.use { cursor ->
				cursor.moveToFirst()
				ContentSyncDto(cursor, tags)
			},
		)
	}

	private fun getTag(authority: String, tagId: Long): ContentTagSyncDto = requireNotNull(
		provider.query(
			uri(authority, TABLE_TAGS),
			null,
			"tag_id = ?",
			arrayOf(tagId.toString()),
			null,
		)?.use { cursor ->
			if (cursor.moveToFirst()) {
				ContentTagSyncDto(cursor)
			} else {
				null
			}
		},
	)

	private fun gcFavourites() {
		val deletedAt = System.currentTimeMillis() - defaultGcPeriod
		val selection = "deleted_at != 0 AND deleted_at < ?"
		val args = arrayOf(deletedAt.toString())
		provider.delete(uri(authorityFavourites, TABLE_WORK_FAVOURITES), selection, args)
		provider.delete(uri(authorityFavourites, TABLE_FAVOURITE_CATEGORIES), selection, args)
	}

	private fun gcHistory() {
		val deletedAt = System.currentTimeMillis() - defaultGcPeriod
		val selection = "deleted_at != 0 AND deleted_at < ?"
		val args = arrayOf(deletedAt.toString())
		provider.delete(uri(authorityHistory, TABLE_WORK_HISTORY), selection, args)
	}

	private fun ContentProviderClient.query(authority: String, table: String): Cursor {
		val uri = uri(authority, table)
		return query(uri, null, null, null, null)
			?: throw OperationApplicationException("Query failed: $uri")
	}

	private fun uri(authority: String, table: String) = "content://$authority/$table".toUri()

	private fun Response.parseDtoOrNull(): SyncDto? = use {
		when {
			!isSuccessful -> throw IOException(body.string())
			code == HttpURLConnection.HTTP_NO_CONTENT -> null
			else -> Json.decodeFromString<SyncDto>(body.string())
		}
	}

	@AssistedFactory
	interface Factory {

		fun create(
			account: Account,
			contentProviderClient: ContentProviderClient,
		): SyncHelper
	}
}
