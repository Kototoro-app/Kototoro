package org.skepsun.kototoro.core.parser

import dagger.Reusable
import org.skepsun.kototoro.core.db.MangaDatabase
import org.skepsun.kototoro.core.db.dao.MangaDao
import org.skepsun.kototoro.core.db.entity.MangaEntity
import org.skepsun.kototoro.core.db.entity.MangaWithTags
import org.skepsun.kototoro.core.model.ContentIdentityKeys
import org.skepsun.kototoro.core.model.LocalNovelSource
import org.skepsun.kototoro.core.model.isLocal
import org.skepsun.kototoro.local.data.output.LocalContentOutput
import org.skepsun.kototoro.parsers.model.Content
import org.skepsun.kototoro.parsers.util.longHashCode
import java.io.ByteArrayOutputStream
import java.io.File
import javax.inject.Inject

@Reusable
class StoredContentIdentityResolver @Inject constructor(
    private val db: MangaDatabase,
) {

    suspend fun resolveStoredContent(content: Content): Content {
        if (content.isLocal) {
            return resolveStoredLocalContent(content)
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

    /**
     * Re-anchors local EPUB folders without `index.json` to their existing library row instead of
     * detaching user state, while keeping non-novel local items, SAF URIs, single-file novels, and
     * folders with `index.json` at zero DB queries.
     *
     * Before EPUB folders without `index.json` unified on `rootUri.longHashCode()`, library scans
     * stored `rootUri.longHashCode()` while opening the details screen parsed via `File` and keyed
     * favourites/history off `rootFile.absolutePath.longHashCode()`. Because `MangaDao.cleanup`
     * retains non-indexed rows only when user state (favourites, history, bookmarks, preferences,
     * stats) references them, an existing legacy path-hashed row for `LocalNovelSource` always
     * carries user state that should keep its id.
     */
    private suspend fun resolveStoredLocalContent(content: Content): Content {
        if (content.source != LocalNovelSource) {
            return content
        }
        val folderPath = localNovelFolderPath(content.url) ?: return content
        val legacyId = folderPath.longHashCode()
        if (legacyId == content.id) {
            return content
        }
        val dao = db.getMangaDao()
        dao.findLocalFolderRowIdByIds(
            source = content.source.name,
            legacyId = legacyId,
            currentId = content.id,
        )?.let { matchedId ->
            return if (matchedId != content.id) content.copy(id = matchedId) else content
        }
        content.findContainedLocalRowId(dao, folderPath)?.let { existingId ->
            return if (existingId != content.id) content.copy(id = existingId) else content
        }
        return content
    }

    private suspend fun Content.findContainedLocalRowId(dao: MangaDao, folderPath: String): Long? {
        val sep = File.separatorChar
        val pathPrefix = if (folderPath.endsWith(sep)) folderPath else "$folderPath$sep"
        val cleanUrl = url.trim().substringBefore('#').substringBefore('?').trimEnd('/')
        val encodedUriPrefix = if (cleanUrl.isNotEmpty()) "$cleanUrl/" else "file://$pathPrefix"
        return dao.findLocalFolderRowIdByPath(
            source = source.name,
            title = title,
            url = url,
            publicUrl = publicUrl,
            pathPrefix = pathPrefix,
            fileUriPrefix = "file://$pathPrefix",
            singleSlashUriPrefix = "file:$pathPrefix",
            encodedUriPrefix = encodedUriPrefix,
            legacyEpubPrefix = "localepub://$pathPrefix",
        )
    }

    private fun localNovelFolderPath(raw: String): String? {
        val decoded = decodedLocalFilePath(raw) ?: return null
        val lower = decoded.lowercase()
        if (
            lower.endsWith(".epub") ||
            lower.endsWith(".txt") ||
            lower.endsWith(".cbz") ||
            lower.endsWith(".zip") ||
            lower.endsWith(".fb2") ||
            lower.endsWith(".pdf")
        ) {
            return null
        }
        val file = File(decoded)
        if (file.exists() && (!file.isDirectory || File(file, LocalContentOutput.ENTRY_NAME_INDEX).isFile)) {
            return null
        }
        return decoded
    }

    private fun decodedLocalFilePath(raw: String): String? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null
        val withoutChapter = trimmed.lastIndexOf("#chapter/").let { idx ->
            if (idx > 0) trimmed.substring(0, idx) else trimmed
        }
        val rawPath = when {
            withoutChapter.startsWith("/") -> withoutChapter
            withoutChapter.startsWith("file://", ignoreCase = true) ->
                withoutChapter.substring("file://".length).substringBefore('#').substringBefore('?')
            withoutChapter.startsWith("file:", ignoreCase = true) ->
                withoutChapter.substring("file:".length).substringBefore('#').substringBefore('?')
            withoutChapter.startsWith("localepub://", ignoreCase = true) ->
                withoutChapter.substring("localepub://".length).substringBefore('#').substringBefore('?')
            else -> return null
        }
        if (rawPath.isBlank()) return null
        val decoded = if (withoutChapter.startsWith("/")) rawPath else percentDecodeUtf8(rawPath)
        return File(decoded).absolutePath
    }

    private fun percentDecodeUtf8(value: String): String {
        if ('%' !in value) return value
        val out = ByteArrayOutputStream(value.length)
        var i = 0
        while (i < value.length) {
            val ch = value[i]
            if (ch == '%' && i + 2 < value.length) {
                val hi = Character.digit(value[i + 1], 16)
                val lo = Character.digit(value[i + 2], 16)
                if (hi >= 0 && lo >= 0) {
                    out.write((hi shl 4) or lo)
                    i += 3
                    continue
                }
            }
            val bytes = ch.toString().toByteArray(Charsets.UTF_8)
            out.write(bytes, 0, bytes.size)
            i++
        }
        return out.toString(Charsets.UTF_8.name())
    }

    /**
     * Keeps the remote identity already stored for this row when [content] carries none.
     *
     * Display stubs (feed and favourites cards, reader session snapshots) are built without urls on
     * purpose, and persisting one over an existing record used to blank `url`/`publicUrl`. Parser
     * sources address their content by exactly that value, so erasing it permanently breaks every
     * later details load — Komiic sends it as the GraphQL comic id, so an empty value queries
     * `comicById("")` and the details screen dies with a parser error.
     *
     * Content that has its own identity is returned untouched, so a real refresh still wins.
     */
    suspend fun preserveStoredRemoteIdentity(content: Content): Content {
        if (content.isLocal || content.hasRemoteIdentityKey()) {
            return content
        }
        val existing = db.getMangaDao().find(content.id)?.manga ?: return content
        if (existing.url.isBlank() && existing.publicUrl.isBlank()) {
            return content
        }
        return content.copy(
            url = existing.url.ifBlank { content.url },
            publicUrl = existing.publicUrl.ifBlank { content.publicUrl },
        )
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
        return ContentIdentityKeys.hasSameIdentity(
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
