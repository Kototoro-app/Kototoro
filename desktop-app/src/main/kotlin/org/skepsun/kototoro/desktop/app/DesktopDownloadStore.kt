package org.skepsun.kototoro.desktop.app

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.skepsun.kototoro.core.source.*
import java.io.IOException
import org.skepsun.kototoro.source.host.FileSourcePreferenceStore

/** Immutable page chunks publish through one per-chapter root; discovery is sharded independently. */
internal class DesktopDownloadStore(private val preferences: SourcePreferenceStore) {
    val errors = mutableListOf<String>()
    private val registered = mutableSetOf<String>()
    private val committedChunks = mutableSetOf<String>()
    private val mutableRecords = MutableStateFlow<List<SourceChapterDownload>>(emptyList())
    val records = mutableRecords.asStateFlow()

    init {
        val discovered = mutableSetOf<String>()
        val restored = mutableListOf<SourceChapterDownload>()
        val unreadableShards = mutableSetOf<Char>()
        for (shard in "0123456789abcdef") {
            val entries = try {
                preferences.open("desktop_download_index_v2_$shard").snapshot()
            } catch (_: Exception) {
                unreadableShards.add(shard)
                errors.add(desktopText("error.downloadIndexRead", shard))
                continue
            }
            for ((identity, value) in entries) {
                discovered.add(identity)
                try {
                    require(identity.matches(Regex("[0-9a-f]{64}")) && identity.first() == shard)
                    require(value == SourcePreferenceValue.Toggle(true))
                    restored.add(read(identity))
                    registered.add(identity)
                } catch (_: Exception) {
                    errors.add(desktopText("error.downloadRecordCorrupt", identity))
                }
            }
        }
        mutableRecords.value = restored
        // Legacy snapshots stay intact. A completed new publication always wins over the old flat record.
        val legacy = try { preferences.open("desktop_downloads").snapshot() } catch (_: Exception) {
            errors.add(desktopText("error.downloadLegacyIndex"))
            emptyMap()
        }
        for ((identity, value) in legacy) {
            if (identity in discovered) continue
            val record = try {
                SourceProtocolJson.decodeFromString<SourceChapterDownload>(text(value)).also {
                    require(identity == key(it.contentId, it.chapter))
                }
            } catch (_: Exception) {
                errors.add(desktopText("error.downloadRecordCorrupt", identity))
                continue
            }
            try {
                if (identity.first() in unreadableShards) throw IOException("Unreadable discovery shard")
                save(record)
            } catch (_: IOException) {
                mutableRecords.value += record
                errors.add(desktopText("error.downloadMigrationFailed", identity))
            }
        }
    }

    @Synchronized
    fun save(record: SourceChapterDownload) {
        val identity = key(record.contentId, record.chapter)
        val chunks = record.pages.chunked(SourceDownloadManifest.CHUNK_SIZE).map { pages ->
            val encoded = SourceProtocolJson.encodeToString(SourceDownloadChunk(pages))
            val hash = imageKey(encoded.toByteArray())
            if (hash !in committedChunks) {
                write("desktop_download_pages_v2_$hash", "pages", SourcePreferenceValue.Text(encoded))
                committedChunks.add(hash)
            }
            hash
        }
        val manifest = SourceDownloadManifest(record.contentId, record.chapter, record.extensionRevision,
            record.contentTitle, record.pages.size, chunks)
        write("${MANIFEST}$identity", "manifest",
            SourcePreferenceValue.Text(SourceProtocolJson.encodeToString(manifest)))
        if (identity !in registered) {
            write("desktop_download_index_v2_${identity.first()}", identity, SourcePreferenceValue.Toggle(true))
            registered.add(identity)
        }
        mutableRecords.value = mutableRecords.value.filter { key(it.contentId, it.chapter) != identity } + record
    }

    @Synchronized
    fun previewCleanup(storage: FileSourcePreferenceStore, checkpoint: () -> Unit = {}) =
        DesktopDownloadCleanup.inspect(storage, checkpoint)

    /**
     * Forgets one saved chapter. The index entry goes first, then the manifest: a manifest that is still indexed would
     * be restored on the next start, while an unindexed one turns its blocks into garbage the cleanup pass reclaims.
     */
    @Synchronized
    fun delete(contentId: Long, chapter: SourceChapter, storage: FileSourcePreferenceStore): Boolean {
        val identity = key(contentId, chapter)
        if (mutableRecords.value.none { key(it.contentId, it.chapter) == identity }) return false
        preferences.open("desktop_download_index_v2_${identity.first()}")
            .edit(SourcePreferenceEdit(changes = mapOf(identity to null)))
        registered.remove(identity)
        val manifest = storage.snapshots().firstOrNull { it.namespace == MANIFEST + identity }
        if (manifest != null) storage.removeSnapshot(manifest)
        mutableRecords.value = mutableRecords.value.filter { key(it.contentId, it.chapter) != identity }
        return true
    }

    @Synchronized
    fun cleanup(plan: DesktopDownloadCleanupPlan, storage: FileSourcePreferenceStore,
        checkpoint: () -> Unit = {}): DesktopDownloadCleanupResult {
        val eligible = DesktopDownloadCleanup.inspect(storage, checkpoint).candidates.toSet()
        var removed = 0
        var bytes = 0L
        for (snapshot in plan.candidates) {
            checkpoint()
            if (snapshot !in eligible) continue
            if (storage.removeSnapshot(snapshot)) {
                committedChunks.remove(snapshot.namespace.removePrefix(DesktopDownloadCleanup.CHUNKS))
                removed++
                bytes += snapshot.byteSize
            }
        }
        return DesktopDownloadCleanupResult(removed, bytes, plan.candidates.size - removed)
    }

    private fun read(identity: String): SourceChapterDownload {
        val manifest = SourceProtocolJson.decodeFromString<SourceDownloadManifest>(
            text(preferences.open("${MANIFEST}$identity").snapshot()["manifest"]))
        require(identity == key(manifest.contentId, manifest.chapter))
        val pages = manifest.chunks.flatMapIndexed { index, hash ->
            val encoded = text(preferences.open("desktop_download_pages_v2_$hash").snapshot()["pages"])
            require(imageKey(encoded.toByteArray()) == hash)
            val chunk = SourceProtocolJson.decodeFromString<SourceDownloadChunk>(encoded)
            require(chunk.pages.size == minOf(SourceDownloadManifest.CHUNK_SIZE,
                manifest.pageCount - index * SourceDownloadManifest.CHUNK_SIZE))
            committedChunks.add(hash)
            chunk.pages
        }
        return SourceChapterDownload(manifest.contentId, manifest.chapter, manifest.extensionRevision, pages,
            manifest.contentTitle)
    }

    private fun text(value: SourcePreferenceValue?): String = (value as? SourcePreferenceValue.Text)?.value
        ?: throw IllegalArgumentException("Missing download document")

    private fun write(namespace: String, key: String, value: SourcePreferenceValue) {
        val saved = try {
            preferences.open(namespace).edit(SourcePreferenceEdit(changes = mapOf(key to value)))
        } catch (error: IllegalArgumentException) {
            throw IOException(desktopText("error.downloadRecordTooLarge"), error)
        }
        if (!saved) throw IOException(desktopText("error.downloadSaveFailed"))
    }

    fun find(contentId: Long, chapter: SourceChapter) = records.value.firstOrNull {
        it.contentId == contentId && sameChapter(it.chapter, chapter)
    }

    fun page(page: SourcePage): SourceDownloadPage? = records.value.asReversed().firstNotNullOfOrNull { record ->
        record.pages.firstOrNull { it.page == page && it.artifact != null }
    }

    companion object {
        const val MANIFEST = "desktop_download_chapter_v2_"

        fun key(contentId: Long, chapter: SourceChapter) = imageKey(
            "${chapter.source.name}\u0000$contentId\u0000${chapter.id}".toByteArray())

        internal fun sameChapter(first: SourceChapter, second: SourceChapter) = first.id == second.id &&
            first.source.name == second.source.name && first.url == second.url && first.branch == second.branch
    }
}
