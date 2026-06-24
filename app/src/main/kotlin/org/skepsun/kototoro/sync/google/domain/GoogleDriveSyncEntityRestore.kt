package org.skepsun.kototoro.sync.google.domain

import org.skepsun.kototoro.core.db.MangaDatabase
import org.skepsun.kototoro.entitygraph.data.EntityGraphDao
import org.skepsun.kototoro.entitygraph.data.EntityRecord
import org.skepsun.kototoro.entitygraph.data.computeNameHash
import org.skepsun.kototoro.entitygraph.data.decodeStringList
import org.skepsun.kototoro.entitygraph.data.encodeStringList
import org.skepsun.kototoro.entitygraph.data.mergeAliases

internal suspend fun MangaDatabase.restoreGoogleDriveSyncEntity(remote: EntityRecord): Long {
	val dao = getEntityGraphDao()
	val trimmedName = remote.primaryName.trim()
	val computedHash = computeNameHash(trimmedName)
	val existing = dao.findEntity(remote.id)
		?.takeIf { it.type == remote.type }
	val nameHashOwner = dao.findEntityByTypeAndNameHash(remote.type, computedHash)
	if (nameHashOwner != null && nameHashOwner.id != existing?.id) {
		return dao.upsertGoogleDriveSyncEntity(
			target = nameHashOwner,
			remote = remote,
			remotePrimaryName = trimmedName,
		)
	}
	if (existing == null) {
		val newRecord = remote.toNormalizedGoogleDriveSyncEntity(
			primaryName = trimmedName,
			nameHash = computedHash,
		)
		val insertedId = dao.insertEntityIgnore(newRecord)
		if (insertedId != -1L) {
			return insertedId
		}
		return dao.findEntityByTypeAndNameHash(newRecord.type, newRecord.nameHash)?.id
			?: dao.insertEntity(newRecord)
	}
	return dao.upsertGoogleDriveSyncEntity(
		target = existing,
		remote = remote,
		remotePrimaryName = trimmedName,
	)
}

private suspend fun EntityGraphDao.upsertGoogleDriveSyncEntity(
	target: EntityRecord,
	remote: EntityRecord,
	remotePrimaryName: String,
): Long {
	val merged = target.mergeWithGoogleDriveSyncEntity(remote, remotePrimaryName)
	val nameHashOwner = findEntityByTypeAndNameHash(merged.type, merged.nameHash)
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
): EntityRecord {
	return copy(
		id = 0L,
		primaryName = primaryName,
		nameHash = nameHash,
		aliases = encodeStringList(mergeAliases(primaryName, decodeStringList(aliases)).drop(1)),
		createdAt = createdAt.coerceAtLeast(0L),
		lastAccessed = lastAccessed.coerceAtLeast(0L),
		accessCount = accessCount.coerceAtLeast(1),
	)
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
		primaryName = newPrimary,
		nameHash = computeNameHash(newPrimary),
		aliases = encodeStringList(mergedNames.drop(1)),
		createdAt = minOf(createdAt, remote.createdAt.coerceAtLeast(0L)),
		lastAccessed = maxOf(lastAccessed, remote.lastAccessed.coerceAtLeast(0L)),
		accessCount = maxOf(accessCount, remote.accessCount.coerceAtLeast(1)),
	)
}
