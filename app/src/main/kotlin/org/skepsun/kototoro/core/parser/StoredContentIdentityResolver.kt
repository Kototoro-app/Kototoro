package org.skepsun.kototoro.core.parser

import dagger.Reusable
import org.skepsun.kototoro.core.db.MangaDatabase
import org.skepsun.kototoro.core.db.dao.MangaDao
import org.skepsun.kototoro.core.db.entity.MangaEntity
import org.skepsun.kototoro.core.db.entity.MangaWithTags
import org.skepsun.kototoro.core.model.ContentIdentityKeys
import org.skepsun.kototoro.core.model.isLocal
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
     * Re-anchors locally parsed content to its existing library row instead of detaching user state.
     *
     * Before EPUB folders without `index.json` unified on `rootUri.longHashCode()`, library scans
     * stored `rootUri.longHashCode()` while opening the details screen parsed via `File` and keyed
     * favourites/history off `rootFile.absolutePath.longHashCode()`. Because `MangaDao.cleanup`
     * retains non-indexed rows only when user state (favourites, history, bookmarks, preferences,
     * stats) references them, an existing legacy path-hashed row for the same local source always
     * carries user state that should keep its id.
     */
    private suspend fun resolveStoredLocalContent(content: Content): Content {
        val dao = db.getMangaDao()
        content.legacyLocalPathId()?.takeIf { it != content.id }?.let { legacyId ->
            val legacyRow = dao.find(legacyId)?.manga
            if (legacyRow != null && legacyRow.source == content.source.name) {
                return content.copy(id = legacyId)
            }
        }
        dao.findBySameRemoteIdentity(content)?.let { existing ->
            return content.copy(id = existing.manga.id)
        }
        if (dao.find(content.id)?.manga == null) {
            content.reanchorToExistingLocalRow(dao)?.let { existing ->
                return content.copy(id = existing.manga.id)
            }
        }
        return content
    }

    private suspend fun Content.reanchorToExistingLocalRow(dao: MangaDao): MangaWithTags? {
        val myPaths = listOf(url, publicUrl)
            .filter(String::isNotBlank)
            .mapNotNull(::decodedLocalFilePath)
            .toSet()
        if (myPaths.isEmpty()) return null
        return dao.findAllBySource(source.name).firstOrNull { row ->
            row.manga.title == title &&
                listOf(row.manga.url, row.manga.publicUrl)
                    .filter(String::isNotBlank)
                    .mapNotNull(::decodedLocalFilePath)
                    .any { hisPath ->
                        myPaths.any { myPath -> pathsOverlap(hisPath, myPath) }
                    }
        }
    }

    private fun Content.legacyLocalPathId(): Long? =
        decodedLocalFilePath(url)?.longHashCode()

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

    private fun pathsOverlap(a: String, b: String): Boolean {
        if (a == b) return true
        val sep = File.separatorChar
        val aPrefix = if (a.endsWith(sep)) a else "$a$sep"
        val bPrefix = if (b.endsWith(sep)) b else "$b$sep"
        return a.startsWith(bPrefix) || b.startsWith(aPrefix)
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
