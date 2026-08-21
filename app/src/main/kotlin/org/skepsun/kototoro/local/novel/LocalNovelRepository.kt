package org.skepsun.kototoro.local.novel

import androidx.annotation.WorkerThread
import androidx.core.net.toUri
import android.util.Log
import com.github.tvbox.osc.base.App
import com.hippo.unifile.UniFile
import kotlinx.coroutines.runBlocking
import org.skepsun.kototoro.core.model.LocalNovelSource
import org.skepsun.kototoro.core.parser.ContentRepository
import org.skepsun.kototoro.local.data.LocalStorageManager
import org.skepsun.kototoro.local.data.importer.LocalImportSupport
import org.skepsun.kototoro.local.data.input.LocalContentParser
import org.skepsun.kototoro.local.epub.parseEpubChapterReference
import org.skepsun.kototoro.local.domain.model.LocalContent
import org.skepsun.kototoro.parsers.model.Content
import org.skepsun.kototoro.parsers.model.ContentChapter
import org.skepsun.kototoro.parsers.model.ContentListFilter
import org.skepsun.kototoro.parsers.model.ContentListFilterCapabilities
import org.skepsun.kototoro.parsers.model.ContentListFilterOptions
import org.skepsun.kototoro.parsers.model.ContentPage
import org.skepsun.kototoro.parsers.model.SortOrder
import java.io.File
import java.util.EnumSet
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LocalNovelRepository @Inject constructor(
    private val storageManager: LocalStorageManager,
) : ContentRepository {

    override val source = LocalNovelSource

    override val sortOrders: Set<SortOrder> = EnumSet.of(SortOrder.ALPHABETICAL)
    override var defaultSortOrder: SortOrder
        get() = SortOrder.ALPHABETICAL
        set(@Suppress("UNUSED_PARAMETER") value) {}

    override val filterCapabilities: ContentListFilterCapabilities
        get() = ContentListFilterCapabilities(isSearchSupported = true)

    override suspend fun getFilterOptions(): ContentListFilterOptions = ContentListFilterOptions()

    private suspend fun findNovelEntries(): List<UniFile> {
        return storageManager.getNovelReadableRoots().flatMap { root ->
            root.file.listFiles().orEmpty().filter { child ->
                val name = child.name.orEmpty()
                !name.startsWith(LocalImportSupport.IMPORT_STAGING_PREFIX) &&
                    (child.isDirectory || name.substringAfterLast('.', "").lowercase() in NOVEL_FILE_EXTENSIONS)
            }
        }
    }

    override suspend fun getList(offset: Int, order: SortOrder?, filter: ContentListFilter?): List<Content> {
        if (offset > 0) return emptyList()
        val items = mutableListOf<Content>()
        findNovelEntries().forEach { entry ->
            parseIndex(entry)?.let { items.add(it.first) }
        }
        val query = filter?.query?.trim().orEmpty()
        return if (query.isNotEmpty()) {
            items.filter { it.title.contains(query, ignoreCase = true) }
        } else items
    }


    override suspend fun getDetails(manga: Content): Content {
        runCatching {
            LocalContentParser(manga.url.toUri()).getContent(withDetails = true).manga
        }.getOrNull()?.let { return it.withLocalNovelSource() }

        // Try to find by filename pattern first (for multi-CBZ format)
        val entries = findNovelEntries()
        val dirByName = entries.firstOrNull { it.name?.startsWith(manga.id.toString()) == true }
        if (dirByName != null) {
            return parseIndex(dirByName)?.first ?: manga
        }

        // For single CBZ files, we need to parse each file to find the matching manga ID
        for (entry in entries) {
            val parsed = parseIndex(entry)
            if (parsed != null && parsed.first.id == manga.id) {
                return parsed.first
            }
        }

        return manga
    }

    override suspend fun getPages(chapter: ContentChapter, nextChapterUrl: String?): List<ContentPage> {
        val uri = runCatching { android.net.Uri.parse(chapter.url) }.getOrNull()
        if (chapter.url.startsWith("epub://") || parseEpubChapterReference(chapter.url) != null) {
            return listOf(ContentPage(chapter.id, chapter.url, null, source))
        }
        if (uri != null && uri.scheme in setOf("content", "file", "zip", "cbz")) {
            return LocalContentParser(uri).getPages(chapter)
        }

        return listOf(
            ContentPage(
                id = chapter.id,
                url = chapter.url,
                preview = null,
                source = source,
            ),
        )
    }

    override suspend fun getPageUrl(page: ContentPage): String = page.url

    override suspend fun getRelated(seed: Content): List<Content> = emptyList()

    /**
     * 列出所有本地小说（用于本地索引）。
     */
    suspend fun getAllLocalNovels(): List<LocalContent> {
        return findNovelEntries().mapNotNull { entry ->
            runCatching { LocalContentParser(entry, App.getInstance().cacheDir).getContent(withDetails = false) }
                .getOrNull()
                ?.let { it.copy(manga = it.manga.withLocalNovelSource()) }
        }
    }

    /**
     * 从目录读取小说并包装为 LocalContent；若目录不合法返回 null。
     */
    fun getLocalNovel(dir: File, withDetails: Boolean): LocalContent? {
        val parsed = parseIndex(dir) ?: return null
        val manga = if (withDetails) {
            parsed.first
        } else {
            parsed.first.copy(chapters = null)
        }
        return LocalContent(manga, dir)
    }

    @WorkerThread
    internal fun parseIndex(dir: File): Pair<Content, List<ContentChapter>>? {
        return runCatching {
            val parser = org.skepsun.kototoro.local.data.input.LocalContentParser(dir)
            val localContent = runBlocking { parser.getContent(withDetails = true) }

            // Map chapters to ensure they have the correct local source when applicable
            val transformedContent = localContent.manga.withLocalNovelSource()
            val transformedChapters = transformedContent.chapters
            transformedContent to (transformedChapters ?: emptyList())
        }.onFailure {
            it.printStackTrace()
        }.getOrNull()
    }

    private suspend fun parseIndex(entry: UniFile): Pair<Content, List<ContentChapter>>? = runCatching {
        val localContent = LocalContentParser(entry, App.getInstance().cacheDir).getContent(withDetails = true)
        val transformedContent = localContent.manga.withLocalNovelSource()
        transformedContent to transformedContent.chapters.orEmpty()
    }.onFailure {
        Log.w("LocalNovelRepository", "Cannot parse local novel: ${entry.uri}", it)
    }.getOrNull()

    private fun Content.withLocalNovelSource(): Content {
        val transformedChapters = chapters?.map { chapter ->
            if (!chapter.source.name.startsWith("LOCAL", ignoreCase = true)) {
                chapter
            } else {
                chapter.copy(source = this@LocalNovelRepository.source)
            }
        }
        return copy(chapters = transformedChapters, source = this@LocalNovelRepository.source)
    }

    private companion object {
        val NOVEL_FILE_EXTENSIONS = setOf("cbz", "epub", "zip")
    }
}
