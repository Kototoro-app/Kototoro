package org.skepsun.kototoro.sync.google.domain

import org.skepsun.kototoro.core.db.entity.MangaEntity
import org.skepsun.kototoro.core.model.ContentIdentityKeys
import org.skepsun.kototoro.sync.google.data.model.SyncContent

/** The manga table as seen by the content step of a sync apply. */
interface SyncContentStore {
    suspend fun findByIdentity(content: SyncContent): MangaEntity?
    suspend fun findById(id: Long): MangaEntity?
    suspend fun contains(id: Long): Boolean
    suspend fun findMinId(): Long?
    suspend fun upsert(entity: MangaEntity)
}

/** Remote content id -> local manga id, as decided by [importSyncContent]. */
class SyncContentImport internal constructor(
    private val mapping: Map<Long, Long>,
    private val skipped: Set<Long>,
) {
    /**
     * The local manga a remote row belongs to, or null when its content was skipped. Ids the
     * snapshot carries no content for (legacy work anchors) resolve to themselves.
     */
    fun localIdOf(remoteId: Long): Long? = if (remoteId in skipped) null else mapping[remoteId] ?: remoteId
}

/**
 * Matches every remote content to a local manga row, inserting the ones that are new.
 * A remote id taken locally by a different content gets a fresh negative id.
 *
 * Content with neither url nor public url is skipped: it can never match a local row, so
 * importing it would add another unopenable copy on every sync.
 */
suspend fun importSyncContent(
    content: List<SyncContent>,
    store: SyncContentStore,
): SyncContentImport {
    val mapping = LinkedHashMap<Long, Long>()
    val skipped = HashSet<Long>()
    var nextImportedMangaId = minOf(store.findMinId() ?: 0L, 0L) - 1L
    content.forEach { remote ->
        if (remote.url.isBlank() && remote.publicUrl.isBlank()) {
            skipped += remote.id
            return@forEach
        }
        val existingByIdentity = store.findByIdentity(remote)
        val existingById = store.findById(remote.id)
        val local = existingByIdentity ?: existingById?.takeIf { it.hasSameContentIdentity(remote) } ?: run {
            val localId = if (existingById != null || store.contains(remote.id)) {
                // Content inserted earlier in this loop may already hold the next id.
                while (store.contains(nextImportedMangaId)) {
                    nextImportedMangaId--
                }
                nextImportedMangaId--
            } else {
                remote.id
            }
            remote.toEntity(localId)
        }
        if (existingByIdentity == null && existingById?.id != local.id) {
            store.upsert(local)
        }
        mapping[remote.id] = local.id
    }
    return SyncContentImport(mapping, skipped)
}

private fun MangaEntity.hasSameContentIdentity(remote: SyncContent): Boolean {
    return ContentIdentityKeys.hasSameIdentity(
        source = source,
        url = url,
        publicUrl = publicUrl,
        otherSource = remote.source,
        otherUrl = remote.url,
        otherPublicUrl = remote.publicUrl,
    )
}
