package org.skepsun.kototoro.core.parser

import dagger.Reusable
import org.skepsun.kototoro.core.db.MangaDatabase
import org.skepsun.kototoro.core.db.dao.MangaDao
import org.skepsun.kototoro.core.db.entity.MangaEntity
import org.skepsun.kototoro.core.db.entity.MangaWithTags
import org.skepsun.kototoro.core.model.ProjectionIdentityKeys
import org.skepsun.kototoro.core.model.isLocal
import org.skepsun.kototoro.parsers.model.Content
import javax.inject.Inject

@Reusable
class ProjectionIdentityResolver @Inject constructor(
	private val db: MangaDatabase,
) {

	suspend fun resolveStoredProjection(content: Content): Content {
		if (content.isLocal) {
			return content
		}
		val dao = db.getMangaDao()
		dao.findBySameRemoteIdentity(content)?.let { existing ->
			return content.copy(id = existing.manga.id)
		}
		if (!content.hasRemoteIdentityKey()) {
			return content
		}
		val existing = dao.find(content.id)?.manga ?: return content
		if (existing.hasSameRemoteIdentity(content)) {
			return content
		}
		return content.copy(id = dao.nextImportedMangaId())
	}

	private suspend fun MangaDao.findBySameRemoteIdentity(content: Content): MangaWithTags? {
		if (content.url.isNotBlank()) {
			findBySourceAndUrl(content.source.name, content.url)?.let { return it }
		}
		if (content.publicUrl.isNotBlank()) {
			findBySourceAndPublicUrl(content.source.name, content.publicUrl)?.let { return it }
		}
		return null
	}

	private suspend fun MangaDao.nextImportedMangaId(): Long {
		var candidate = minOf(findMinId() ?: 0L, 0L) - 1L
		while (contains(candidate)) {
			candidate--
		}
		return candidate
	}

	private fun MangaEntity.hasSameRemoteIdentity(other: Content): Boolean {
		return ProjectionIdentityKeys.hasSameIdentity(
			source = source,
			url = url,
			publicUrl = publicUrl,
			otherSource = other.source.name,
			otherUrl = other.url,
			otherPublicUrl = other.publicUrl,
		)
	}

	private fun Content.hasRemoteIdentityKey(): Boolean {
		return url.isNotBlank() || publicUrl.isNotBlank()
	}
}
