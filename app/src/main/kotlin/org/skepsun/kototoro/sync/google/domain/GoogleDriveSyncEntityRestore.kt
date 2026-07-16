package org.skepsun.kototoro.sync.google.domain

import org.skepsun.kototoro.core.db.MangaDatabase
import org.skepsun.kototoro.entitygraph.data.EntityGraphDao
import org.skepsun.kototoro.entitygraph.data.EntityRecord
import org.skepsun.kototoro.entitygraph.data.computeProjectionSyncId
import org.skepsun.kototoro.entitygraph.data.computeNameHash
import org.skepsun.kototoro.entitygraph.data.decodeStringList
import org.skepsun.kototoro.entitygraph.data.encodeStringList
import org.skepsun.kototoro.entitygraph.data.mergeAliases

internal suspend fun MangaDatabase.restoreGoogleDriveSyncEntity(remote: EntityRecord): Long {
	val dao = getEntityGraphDao()
	val trimmedName = remote.primaryName.trim()
	val computedHash = computeNameHash(trimmedName)
	val syncIdOwner = remote.syncId.trim()
		.takeIf { it.isNotEmpty() }
		?.let { dao.findEntityBySyncId(it) }
		?.takeIf { it.isCompatibleWith(remote) }
	if (syncIdOwner != null) {
	return dao.upsertGoogleDriveSyncEntity(
			target = syncIdOwner,
			remote = remote,
			remotePrimaryName = trimmedName,
		)
	}
	val existing = dao.findEntity(remote.id)
		?.takeIf { it.isCompatibleWith(remote) }
	val nameHashOwner = dao.findEntityByTypeAndNameHashAndContentType(remote.type, computedHash, remote.contentType)
	if (nameHashOwner != null && nameHashOwner.id != existing?.id) {
		return dao.upsertGoogleDriveSyncEntity(
			target = nameHashOwner,
			remote = remote,
			remotePrimaryName = trimmedName,
		)
	}
	if (existing == null) {
		val syncId = remote.syncId.trim()
			.takeIf { it.isNotEmpty() }
			?.let { candidate ->
				dao.findEntityBySyncId(candidate)
					?.takeUnless { it.isCompatibleWith(remote) }
					?.let { java.util.UUID.randomUUID().toString() }
					?: candidate
			}
			.orEmpty()
		val newRecord = remote.toNormalizedGoogleDriveSyncEntity(
			primaryName = trimmedName,
			nameHash = computedHash,
			syncId = syncId,
		)
		val insertedId = dao.insertEntityIgnore(newRecord)
		if (insertedId != -1L) {
			return insertedId
		}
		return dao.findEntityByTypeAndNameHashAndContentType(newRecord.type, newRecord.nameHash, newRecord.contentType)?.id
			?: dao.insertEntity(newRecord)
	}
		return dao.upsertGoogleDriveSyncEntity(
		target = existing,
		remote = remote,
		remotePrimaryName = trimmedName,
	)
}

internal suspend fun MangaDatabase.restoreGoogleDriveSyncEntityIsolated(remote: EntityRecord): Long {
	val dao = getEntityGraphDao()
	remote.syncId.trim()
		.takeIf { it.isNotEmpty() }
		?.let { dao.findEntityBySyncId(it) }
		?.takeIf { it.isCompatibleWith(remote) }
		?.let { return it.id }
	val trimmedName = remote.primaryName.trim()
	val syncId = remote.syncId.trim()
		.takeIf { it.isNotEmpty() }
		?.let { candidate ->
			dao.findEntityBySyncId(candidate)
				?.takeUnless { it.isCompatibleWith(remote) }
				?.let { java.util.UUID.randomUUID().toString() }
				?: candidate
		}
		.orEmpty()
	val newRecord = remote.toNormalizedGoogleDriveSyncEntity(
		primaryName = trimmedName,
		nameHash = computeIsolatedGoogleDriveSyncNameHash(
			remoteId = remote.id,
			type = remote.type,
				primaryName = trimmedName,
				createdAt = remote.createdAt,
			),
		syncId = syncId,
	)
	val insertedId = dao.insertEntityIgnore(newRecord)
	if (insertedId != -1L) {
		return insertedId
	}
	return dao.findEntityByTypeAndNameHashAndContentType(newRecord.type, newRecord.nameHash, newRecord.contentType)?.id
		?: dao.insertEntity(
			newRecord.copy(
				nameHash = computeIsolatedGoogleDriveSyncNameHash(
					remoteId = remote.id,
					type = remote.type,
					primaryName = trimmedName,
					createdAt = System.currentTimeMillis(),
				),
			),
		)
}

private suspend fun EntityGraphDao.upsertGoogleDriveSyncEntity(
	target: EntityRecord,
	remote: EntityRecord,
	remotePrimaryName: String,
): Long {
	val merged = target.mergeWithGoogleDriveSyncEntity(remote, remotePrimaryName)
	val nameHashOwner = findEntityByTypeAndNameHashAndContentType(
		merged.type,
		merged.nameHash,
		merged.contentType,
	)
	if (nameHashOwner != null && nameHashOwner.id != target.id) {
		val remapped = nameHashOwner.mergeWithGoogleDriveSyncEntity(merged, merged.primaryName)
		upsertEntityRecord(remapped)
		return nameHashOwner.id
	}
	upsertEntityRecord(merged)
	return target.id
}

private fun EntityRecord.toNormalizedGoogleDriveSyncEntity(
	primaryName: String,
	nameHash: Long,
	syncId: String = this.syncId,
): EntityRecord {
	return copy(
		id = 0L,
		syncId = syncId.ifBlank { java.util.UUID.randomUUID().toString() },
		primaryName = primaryName,
		nameHash = nameHash,
		aliases = encodeStringList(mergeAliases(primaryName, decodeStringList(aliases)).drop(1)),
		createdAt = createdAt.coerceAtLeast(0L),
		lastAccessed = lastAccessed.coerceAtLeast(0L),
		accessCount = accessCount.coerceAtLeast(1),
	)
}

private fun computeIsolatedGoogleDriveSyncNameHash(
	remoteId: Long,
	type: String,
	primaryName: String,
	createdAt: Long,
): Long {
	return computeNameHash("$type|$primaryName|google_drive_sync|$remoteId|${createdAt.coerceAtLeast(0L)}")
}

private fun EntityRecord.mergeWithGoogleDriveSyncEntity(
	remote: EntityRecord,
	remotePrimaryName: String,
): EntityRecord {
	val mergedNames = mergeAliases(
		primaryName,
		decodeStringList(aliases) + listOf(remotePrimaryName) + decodeStringList(remote.aliases),
	)
	val newPrimary = mergedNames.firstOrNull() ?: primaryName
	return copy(
		contentType = contentType ?: remote.contentType,
		primaryName = newPrimary,
		nameHash = computeNameHash(newPrimary),
		aliases = encodeStringList(mergedNames.drop(1)),
		createdAt = minOf(createdAt, remote.createdAt.coerceAtLeast(0L)),
		lastAccessed = maxOf(lastAccessed, remote.lastAccessed.coerceAtLeast(0L)),
		accessCount = maxOf(accessCount, remote.accessCount.coerceAtLeast(1)),
	)
}

private fun EntityRecord.isCompatibleWith(remote: EntityRecord): Boolean {
	return type == remote.type &&
		(contentType == null || remote.contentType == null || contentType == remote.contentType)
}
