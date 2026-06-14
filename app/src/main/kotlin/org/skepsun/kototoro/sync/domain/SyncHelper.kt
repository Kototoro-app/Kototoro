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
import java.net.HttpURLConnection
import java.util.concurrent.TimeUnit

class SyncHelper @AssistedInject constructor(
	@ApplicationContext context: Context,
	@BaseHttpClient baseHttpClient: OkHttpClient,
	@Assisted private val account: Account,
	@Assisted private val provider: ContentProviderClient,
	private val settings: SyncSettings,
	private val db: MangaDatabase,
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
		val uri = uri(authorityHistory, TABLE_HISTORY)
		val operations = ArrayList<ContentProviderOperation>()
		history.forEach {
			operations.addAll(upsertContent(it.manga, authorityHistory))
			operations += ContentProviderOperation.newInsert(uri)
				.withValues(it.toContentValues())
				.build()
		}
		val result = provider.applyBatch(operations)
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
		val uri = uri(authorityFavourites, TABLE_FAVOURITES)
		val operations = ArrayList<ContentProviderOperation>()
		favourites.forEach {
			operations.addAll(upsertContent(it.manga, authorityFavourites))
			operations += ContentProviderOperation.newInsert(uri)
				.withValues(it.toContentValues())
				.build()
		}
		val result = provider.applyBatch(operations)
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
		if (workHistory.isNotEmpty()) {
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
		return provider.query(authorityHistory, TABLE_HISTORY).use { cursor ->
			val result = ArrayList<HistorySyncDto>(cursor.count)
			if (cursor.moveToFirst()) {
				do {
					val mangaId = cursor.getLong(cursor.getColumnIndexOrThrow("manga_id"))
					val entityId = findEntityIdByLocalMangaId(mangaId)
					val anchorMangaId = entityId?.let {
						runBlocking { db.getEntityGraphDao().findEntityPrefs(it)?.preferredLocalMangaId }
					}
					val base = HistorySyncDto(cursor, getContent(authorityHistory, mangaId))
					result.add(
						base.copy(
							entityId = entityId,
							anchorMangaId = anchorMangaId ?: mangaId,
						),
					)
				} while (cursor.moveToNext())
			}
			result
		}
	}

	private fun getFavourites(): List<FavouriteSyncDto> {
		val workFavourites = runBlocking { db.getWorkFavouritesDao().dump().toList() }
		if (workFavourites.isNotEmpty()) {
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
		return provider.query(authorityFavourites, TABLE_FAVOURITES).map { cursor ->
			val mangaId = cursor.getLong(cursor.getColumnIndexOrThrow("manga_id"))
			val manga = getContent(authorityFavourites, mangaId)
			FavouriteSyncDto(cursor, manga).copy(
				entityId = findEntityIdByLocalMangaId(mangaId),
			)
		}
	}

	private fun upsertWorkHistory(dto: HistorySyncDto) {
		val entityId = resolveSyncEntityId(dto.entityId, dto.mangaId) ?: return
		val anchorMangaId = dto.anchorMangaId
			?: resolveExistingLocalAnchorForEntity(entityId)
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
		if (remoteEntityId != null && remoteEntityId > 0L) {
			val localEntityExists = runBlocking {
				db.getEntityGraphDao().findEntity(remoteEntityId) != null
			}
			if (localEntityExists) {
				return remoteEntityId
			}
		}
		return findEntityIdByLocalMangaId(mangaId)
	}

	private fun resolveSyncMangaIdForEntity(entityId: Long, fallbackMangaId: Long? = null): Long? {
		return runBlocking {
			resolveExistingLocalAnchorForEntity(entityId)
				?: fallbackMangaId
		}
	}

	private fun resolveExistingLocalAnchorForEntity(entityId: Long): Long? {
		return runBlocking {
			db.getEntityGraphDao().findEntityPrefs(entityId)?.preferredLocalMangaId
				?.takeIf { preferredId -> db.getMangaDao().contains(preferredId) }
				?: db.getEntityGraphDao().findActiveBindingsByEntity(entityId)
					.firstNotNullOfOrNull { binding ->
						when (binding.source) {
							"local_manga", "0" -> binding.externalId.toLongOrNull()
								?.takeIf { localId -> db.getMangaDao().contains(localId) }
							else -> null
						}
					}
		}
	}

	private fun findEntityIdByLocalMangaId(mangaId: Long): Long? {
		val dao = db.getEntityGraphDao()
		return runBlocking {
			dao.findActiveBinding("local_manga", mangaId.toString())?.entityId
				?: dao.findActiveBinding("0", mangaId.toString())?.entityId
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
		provider.delete(uri(authorityFavourites, TABLE_FAVOURITES), selection, args)
		provider.delete(uri(authorityFavourites, TABLE_FAVOURITE_CATEGORIES), selection, args)
	}

	private fun gcHistory() {
		val deletedAt = System.currentTimeMillis() - defaultGcPeriod
		val selection = "deleted_at != 0 AND deleted_at < ?"
		val args = arrayOf(deletedAt.toString())
		provider.delete(uri(authorityHistory, TABLE_HISTORY), selection, args)
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
