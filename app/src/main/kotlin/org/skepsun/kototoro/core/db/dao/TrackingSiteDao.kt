package org.skepsun.kototoro.core.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow
import org.skepsun.kototoro.core.db.entity.TrackingSiteItemEntity
import org.skepsun.kototoro.core.db.entity.TrackingSiteLinkEntity

@Dao
abstract class TrackingSiteDao {

	@Query("SELECT * FROM tracking_site_items WHERE service = :service AND remote_id = :remoteId")
	abstract suspend fun findItem(service: Int, remoteId: Long): TrackingSiteItemEntity?

	@Query("SELECT * FROM tracking_site_items WHERE service = :service AND remote_id = :remoteId")
	abstract fun observeItem(service: Int, remoteId: Long): Flow<TrackingSiteItemEntity?>

	@Query("SELECT * FROM tracking_site_items WHERE service = :service ORDER BY updated_at DESC, remote_id DESC")
	abstract fun observeItems(service: Int): Flow<List<TrackingSiteItemEntity>>

	@Query("SELECT * FROM tracking_site_items WHERE service = :service ORDER BY updated_at DESC, remote_id DESC")
	abstract suspend fun findItems(service: Int): List<TrackingSiteItemEntity>

	@Upsert
	abstract suspend fun upsertItem(entity: TrackingSiteItemEntity)

	@Upsert
	abstract suspend fun upsertItems(entities: List<TrackingSiteItemEntity>)

	@Query("DELETE FROM tracking_site_items WHERE service = :service")
	abstract suspend fun deleteItemsByService(service: Int)

	@Query("DELETE FROM tracking_site_items WHERE service = :service AND remote_id = :remoteId")
	abstract suspend fun deleteItem(service: Int, remoteId: Long)

	@Query("SELECT * FROM tracking_site_links WHERE service = :service AND remote_id = :remoteId LIMIT 1")
	abstract suspend fun findLink(service: Int, remoteId: Long): TrackingSiteLinkEntity?

	@Query("SELECT * FROM tracking_site_links WHERE service = :service AND entity_id = :entityId")
	abstract suspend fun findLinksByEntity(service: Int, entityId: Long): List<TrackingSiteLinkEntity>

	@Query("SELECT * FROM tracking_site_links WHERE service = :service AND manga_id = :mangaId")
	abstract suspend fun findLinksByManga(service: Int, mangaId: Long): List<TrackingSiteLinkEntity>

	@Query("SELECT * FROM tracking_site_links WHERE service = :service AND entity_id IN (:entityIds)")
	abstract suspend fun findLinksByEntityIds(service: Int, entityIds: List<Long>): List<TrackingSiteLinkEntity>

	@Query("SELECT * FROM tracking_site_links WHERE service = :service AND manga_id IN (:mangaIds)")
	abstract suspend fun findLinksByMangaIds(service: Int, mangaIds: List<Long>): List<TrackingSiteLinkEntity>

	@Query("SELECT * FROM tracking_site_links WHERE entity_id = :entityId")
	abstract suspend fun findLinksByEntity(entityId: Long): List<TrackingSiteLinkEntity>

	@Query("SELECT * FROM tracking_site_links WHERE manga_id = :mangaId")
	abstract suspend fun findLinksByManga(mangaId: Long): List<TrackingSiteLinkEntity>

	@Query("SELECT * FROM tracking_site_links WHERE entity_id IN (:entityIds)")
	abstract suspend fun findLinksByEntityIds(entityIds: List<Long>): List<TrackingSiteLinkEntity>

	@Query("SELECT * FROM tracking_site_links WHERE manga_id IN (:mangaIds)")
	abstract suspend fun findLinksByMangaIds(mangaIds: List<Long>): List<TrackingSiteLinkEntity>

	@Query("SELECT * FROM tracking_site_links")
	abstract suspend fun findAllLinks(): List<TrackingSiteLinkEntity>

	@Query("SELECT * FROM tracking_site_links WHERE service = :service AND remote_id = :remoteId")
	abstract suspend fun findLinksByRemoteId(service: Int, remoteId: Long): List<TrackingSiteLinkEntity>

	@Query("SELECT * FROM tracking_site_links WHERE service = :service AND remote_id = :remoteId")
	abstract fun observeLinks(service: Int, remoteId: Long): Flow<List<TrackingSiteLinkEntity>>

	@Query("SELECT * FROM tracking_site_links WHERE entity_id = :entityId")
	abstract fun observeLinksByEntity(entityId: Long): Flow<List<TrackingSiteLinkEntity>>

	@Query("SELECT * FROM tracking_site_links WHERE manga_id = :mangaId")
	abstract fun observeLinksByManga(mangaId: Long): Flow<List<TrackingSiteLinkEntity>>

	@Query("SELECT * FROM tracking_site_links WHERE entity_id IN (:entityIds)")
	abstract fun observeLinksByEntityIds(entityIds: List<Long>): Flow<List<TrackingSiteLinkEntity>>

	@Query("SELECT * FROM tracking_site_links WHERE manga_id IN (:mangaIds)")
	abstract fun observeLinksByMangaIds(mangaIds: List<Long>): Flow<List<TrackingSiteLinkEntity>>

	@Upsert
	abstract suspend fun upsertLink(entity: TrackingSiteLinkEntity)

	@Upsert
	abstract suspend fun upsertLinks(entities: List<TrackingSiteLinkEntity>)

	@Query("DELETE FROM tracking_site_links WHERE service = :service AND remote_id = :remoteId AND manga_id = :mangaId")
	abstract suspend fun deleteLink(service: Int, remoteId: Long, mangaId: Long)

	@Query("DELETE FROM tracking_site_links WHERE service = :service AND entity_id = :entityId")
	abstract suspend fun deleteLinksByEntity(service: Int, entityId: Long)

	@Query("DELETE FROM tracking_site_links WHERE service = :service AND manga_id = :mangaId")
	abstract suspend fun deleteLinksByManga(service: Int, mangaId: Long)

	@Query("DELETE FROM tracking_site_links WHERE service = :service AND entity_id IN (:entityIds)")
	abstract suspend fun deleteLinksByEntityIds(service: Int, entityIds: List<Long>)

	@Query("DELETE FROM tracking_site_links")
	abstract suspend fun deleteAllLinks()

	@Query("DELETE FROM tracking_site_links WHERE service = :service AND manga_id IN (:mangaIds)")
	abstract suspend fun deleteLinksByMangaIds(service: Int, mangaIds: List<Long>)

	@Query("DELETE FROM tracking_site_links WHERE service = :service")
	abstract suspend fun deleteLinksByService(service: Int)
}
