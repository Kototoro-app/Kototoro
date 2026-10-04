package org.skepsun.kototoro.desktop.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import org.skepsun.kototoro.core.source.*
import org.skepsun.kototoro.reader.core.IntSize
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

@Serializable
private data class DesktopReaderRecord(
    val artifact: SourceImageArtifact,
    val storedAt: Long,
    val width: Int = 0,
    val height: Int = 0,
)

internal data class DesktopReaderFile(val image: DesktopReaderImage, val artifact: SourceImageArtifact)

/** Bounded persisted artifact index; requests and cancellation remain owned by the caller. */
internal class DesktopReaderImages(
    private val directory: Path,
    private val preferences: SourcePreferences,
    private val maximumRecords: Int = 256,
    private val fetch: suspend (SourcePage) -> SourceImageArtifact,
) {
    private val gate = Mutex()
    init { require(maximumRecords > 0) }

    suspend fun load(page: SourcePage, extensionRevision: String, refresh: Boolean = false): DesktopReaderImage =
        prepare(page, extensionRevision, refresh).image

    suspend fun openArtifact(page: SourcePage, artifact: SourceImageArtifact): DesktopReaderImage? =
        withContext(Dispatchers.IO) {
            require(artifact.pageId == page.id && artifact.source.name == page.source.name)
            verifiedImagePath(directory, artifact.relativePath, artifact.sha256, artifact.byteSize)?.let(::header)
        }

    /** Downloads own their manifest; never hold the foreground cache gate during a background transfer. */
    suspend fun prepareDownload(page: SourcePage, extensionRevision: String,
        known: SourceImageArtifact?): DesktopReaderFile = withContext(Dispatchers.IO) {
        val key = imageKey((extensionRevision + "\u0000" + SourceProtocolJson.encodeToString(page)).toByteArray())
        val cached = known ?: record(preferences.snapshot()[key])?.artifact
            ?.takeIf { it.pageId == page.id && it.source.name == page.source.name }
        cached?.let { artifact ->
            openArtifact(page, artifact)?.let { return@withContext DesktopReaderFile(it, artifact) }
        }
        val artifact = fetch(page)
        val image = requireNotNull(openArtifact(page, artifact)) { "页面缓存文件校验失败" }
        currentCoroutineContext().ensureActive()
        DesktopReaderFile(image, artifact)
    }

    private suspend fun prepare(page: SourcePage, extensionRevision: String, refresh: Boolean): DesktopReaderFile =
        withContext(Dispatchers.IO) {
            gate.withLock {
                val key = imageKey((extensionRevision + "\u0000" + SourceProtocolJson.encodeToString(page)).toByteArray())
                val cachedRecord = if (refresh) null else record(preferences.snapshot()[key])
                val cached = cachedRecord?.artifact
                    ?.takeIf { it.pageId == page.id && it.source.name == page.source.name }
                val cachedPath = cached?.let { verifiedImagePath(directory, it.relativePath, it.sha256, it.byteSize) }
                if (cachedPath != null) {
                    val image = header(cachedPath)
                    if (cachedRecord != null && cachedRecord.artifact == cached &&
                        (cachedRecord.width != image.width || cachedRecord.height != image.height)) {
                        currentCoroutineContext().ensureActive()
                        preferences.edit(SourcePreferenceEdit(changes = mapOf(key to SourcePreferenceValue.Text(
                            SourceProtocolJson.encodeToString(cachedRecord.copy(width = image.width, height = image.height)),
                        ))))
                    }
                    return@withLock DesktopReaderFile(image, cached)
                }
                val artifact = fetch(page)
                require(artifact.pageId == page.id && artifact.source.name == page.source.name) {
                    "来源返回的页面身份不匹配"
                }
                val path = requireNotNull(verifiedImagePath(directory, artifact.relativePath, artifact.sha256, artifact.byteSize)) {
                    "页面缓存文件校验失败"
                }
                val image = header(path)
                currentCoroutineContext().ensureActive()
                val snapshot = preferences.snapshot()
                val surplus = snapshot.size + (if (key in snapshot) 0 else 1) - maximumRecords
                val removals = snapshot.entries.filter { it.key != key }
                    .sortedBy { record(it.value)?.storedAt ?: Long.MIN_VALUE }
                    .take(surplus.coerceAtLeast(0)).map { it.key }.toSet()
                preferences.edit(SourcePreferenceEdit(changes = removals.associateWith { null } + mapOf(
                    key to SourcePreferenceValue.Text(SourceProtocolJson.encodeToString(
                        DesktopReaderRecord(artifact, System.currentTimeMillis(), image.width, image.height),
                    )),
                )))
                DesktopReaderFile(image, artifact)
            }
        }

    /** Geometry is only a hint; the visible artifact still passes load's full integrity check. */
    suspend fun geometry(pages: List<SourcePage>, extensionRevision: String): Map<Long, IntSize> = withContext(Dispatchers.IO) {
        gate.withLock {
            val snapshot = preferences.snapshot()
            buildMap {
                for (page in pages) {
                    val key = imageKey((extensionRevision + "\u0000" + SourceProtocolJson.encodeToString(page)).toByteArray())
                    val record = record(snapshot[key]) ?: continue
                    val artifact = record.artifact
                    val path = directory.resolve(artifact.relativePath)
                    if (record.width > 0 && record.height > 0 && artifact.pageId == page.id &&
                        artifact.source.name == page.source.name && hasExpectedSize(path, artifact.byteSize)) {
                        put(page.id, IntSize(record.width, record.height))
                    }
                }
            }
        }
    }

    private fun hasExpectedSize(path: Path, byteSize: Long): Boolean = try {
        Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) && Files.size(path) == byteSize
    } catch (_: IOException) { false }

    private fun header(path: Path) = DesktopImageDecoder.header(path)

    private fun record(value: SourcePreferenceValue?): DesktopReaderRecord? = try {
        (value as? SourcePreferenceValue.Text)?.let { SourceProtocolJson.decodeFromString<DesktopReaderRecord>(it.value) }
    } catch (_: IllegalArgumentException) { null }
}
