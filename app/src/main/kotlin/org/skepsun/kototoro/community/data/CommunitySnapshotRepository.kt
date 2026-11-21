package org.skepsun.kototoro.community.data

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import org.skepsun.kototoro.community.domain.CommunityChart
import org.skepsun.kototoro.community.domain.CommunityModeration
import org.skepsun.kototoro.community.domain.CommunitySnapshotManifest
import org.skepsun.kototoro.community.domain.CommunityWorkSummary
import org.skepsun.kototoro.community.snapshot.CommunitySnapshotFetcher
import org.skepsun.kototoro.core.library.community.CommunitySnapshotCache
import org.skepsun.kototoro.core.library.community.CommunitySnapshots
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The community index on this device (plan §6.3, §7.3): the shared [CommunitySnapshots] over files under
 * `files/community/snapshot`, each file at its snapshot path with the hash of what was stored beside it.
 */
@Singleton
class CommunitySnapshotRepository @Inject constructor(
    @ApplicationContext context: Context,
    fetcher: CommunitySnapshotFetcher,
) {

    val snapshots = CommunitySnapshots(
        fetch = { path -> fetcher.fetch(path)?.text },
        cache = FileCache(File(context.filesDir, "community/snapshot")),
        io = Dispatchers.IO,
    )

    suspend fun manifest(force: Boolean = false): CommunitySnapshotManifest? = snapshots.manifest(force)

    /** What the index knows about the work named by [keys]; null when nothing, or when the index cannot be reached. */
    suspend fun workSummary(keys: List<String>): CommunityWorkSummary? = snapshots.workSummary(keys)

    /** The group of each work in [keysByWork]; works the index does not know are left out. */
    suspend fun <K> groupsOf(keysByWork: Map<K, List<String>>, cachedOnly: Boolean = false): Map<K, String> =
        snapshots.groupsOf(keysByWork, cachedOnly)

    suspend fun works(groupIds: Collection<String>, cachedOnly: Boolean = false): Map<String, CommunityWorkSummary> =
        snapshots.works(groupIds, cachedOnly)

    suspend fun allWorks(): List<CommunityWorkSummary> = snapshots.allWorks()

    suspend fun chart(window: String, medium: String, nsfw: Boolean): CommunityChart? = snapshots.chart(window, medium, nsfw)

    suspend fun moderation(): CommunityModeration = snapshots.moderation()

    suspend fun relays(): List<String>? = snapshots.relays()

    suspend fun clear() = snapshots.clear()

    private class FileCache(private val root: File) : CommunitySnapshotCache {
        override fun read(path: String): String? = File(root, path).takeIf { it.isFile }?.readText()

        override fun readHash(path: String): String? = File(root, "$path$HASH_SUFFIX").takeIf { it.isFile }?.readText()

        override fun write(path: String, text: String, hash: String) {
            val file = File(root, path)
            file.parentFile?.mkdirs()
            val temp = File(file.parentFile, ".${file.name}.part")
            temp.writeText(text)
            temp.renameTo(file)
            File(root, "$path$HASH_SUFFIX").writeText(hash)
        }

        override fun clear() {
            root.deleteRecursively()
        }
    }

    private companion object {
        const val HASH_SUFFIX = ".sha256"
    }
}
