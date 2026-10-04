package org.skepsun.kototoro.desktop.app

import org.skepsun.kototoro.core.source.*
import org.skepsun.kototoro.source.host.FileSourcePreferenceSnapshot
import org.skepsun.kototoro.source.host.FileSourcePreferenceStore
import java.io.IOException

internal data class DesktopDownloadCleanupPlan(
    val candidates: List<FileSourcePreferenceSnapshot>,
    val metadataBytes: Long,
) {
    val reclaimableBytes: Long get() = candidates.sumOf { it.byteSize }
}

internal data class DesktopDownloadCleanupResult(val removed: Int, val bytes: Long, val skipped: Int)

/** Traverse every stored root, including unpublished discovery entries, before selecting immutable old chunks. */
internal object DesktopDownloadCleanup {
    const val CHUNKS = "desktop_download_pages_v2_"
    private const val ROOTS = "desktop_download_chapter_v2_"

    fun inspect(storage: FileSourcePreferenceStore, checkpoint: () -> Unit): DesktopDownloadCleanupPlan {
        val snapshots = storage.snapshots(checkpoint)
        val chunks = snapshots.filter { it.namespace.startsWith(CHUNKS) }.associateBy { it.namespace.removePrefix(CHUNKS) }
        require(chunks.keys.all { it.matches(Regex("[0-9a-f]{64}")) }) { "下载块身份无效，无法回收" }
        val referenced = mutableSetOf<String>()
        for (root in snapshots.filter { it.namespace.startsWith(ROOTS) }) {
            checkpoint()
            val manifest = SourceProtocolJson.decodeFromString<SourceDownloadManifest>(
                text(storage.readStored(root)["manifest"]))
            require(root.namespace == ROOTS + DesktopDownloadStore.key(manifest.contentId, manifest.chapter))
            val pages = manifest.chunks.flatMapIndexed { index, hash ->
                checkpoint()
                val encoded = text(storage.readStored(chunks[hash] ?: throw IOException("下载清单缺少数据块"))["pages"])
                require(imageKey(encoded.toByteArray()) == hash) { "下载数据块校验失败" }
                val chunk = SourceProtocolJson.decodeFromString<SourceDownloadChunk>(encoded)
                require(chunk.pages.size == minOf(SourceDownloadManifest.CHUNK_SIZE,
                    manifest.pageCount - index * SourceDownloadManifest.CHUNK_SIZE))
                referenced.add(hash)
                chunk.pages
            }
            SourceChapterDownload(manifest.contentId, manifest.chapter, manifest.extensionRevision, pages,
                manifest.contentTitle)
        }
        return DesktopDownloadCleanupPlan(chunks.filterKeys { it !in referenced }.values.toList(),
            snapshots.filter { it.namespace.startsWith("desktop_download") }.sumOf { it.byteSize })
    }

    private fun text(value: SourcePreferenceValue?) = (value as? SourcePreferenceValue.Text)?.value
        ?: throw IOException("下载清单数据不完整")
}
