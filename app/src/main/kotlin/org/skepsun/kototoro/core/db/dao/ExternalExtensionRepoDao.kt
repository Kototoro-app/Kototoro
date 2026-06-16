package org.skepsun.kototoro.core.db.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow
import org.skepsun.kototoro.core.db.entity.ExternalExtensionRepoEntity
import org.skepsun.kototoro.extensions.repo.ExternalExtensionType

@Dao
interface ExternalExtensionRepoDao {

	@Query("SELECT * FROM extension_repos WHERE type = :type ORDER BY name, base_url")
	fun observeByType(type: ExternalExtensionType): Flow<List<ExternalExtensionRepoEntity>>

	@Query("SELECT * FROM extension_repos WHERE type = :type ORDER BY name, base_url")
	suspend fun getByType(type: ExternalExtensionType): List<ExternalExtensionRepoEntity>

	@Query("SELECT * FROM extension_repos WHERE type = :type AND base_url = :baseUrl LIMIT 1")
	suspend fun get(type: ExternalExtensionType, baseUrl: String): ExternalExtensionRepoEntity?

	@Query("SELECT * FROM extension_repos WHERE type = :type AND signing_key_fingerprint = :fingerprint LIMIT 1")
	suspend fun getByFingerprint(type: ExternalExtensionType, fingerprint: String): ExternalExtensionRepoEntity?

	@Upsert
	suspend fun upsert(repo: ExternalExtensionRepoEntity)

	@Delete
	suspend fun delete(repo: ExternalExtensionRepoEntity)

	@Query("DELETE FROM extension_repos WHERE type = :type AND base_url = :baseUrl")
	suspend fun delete(type: ExternalExtensionType, baseUrl: String)

	@Query("DELETE FROM extension_repos")
	suspend fun deleteAll()
}
