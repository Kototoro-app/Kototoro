package org.skepsun.kototoro.details.data

import org.skepsun.kototoro.core.model.getLocale
import org.skepsun.kototoro.core.model.isLocal
import org.skepsun.kototoro.core.model.withOverride
import org.skepsun.kototoro.core.ui.model.ContentOverride
import org.skepsun.kototoro.core.util.ext.takeIfUsableImageUri
import org.skepsun.kototoro.local.domain.model.LocalContent
import org.skepsun.kototoro.parsers.model.Content
import org.skepsun.kototoro.parsers.model.ContentChapter
import org.skepsun.kototoro.parsers.model.ContentState
import org.skepsun.kototoro.parsers.util.ifNullOrEmpty
import org.skepsun.kototoro.parsers.util.nullIfEmpty
import org.skepsun.kototoro.reader.data.filterChapters
import java.util.Locale

data class ContentDetails(
    private val manga: Content,
    private val localContent: LocalContent?,
    private val override: ContentOverride?,
    val description: CharSequence?,
    val isLoaded: Boolean,
) {

    constructor(manga: Content) : this(
        manga = manga,
        localContent = null,
        override = null,
        description = null,
        isLoaded = false,
    )

    val id: Long
        get() = manga.id

    val allChapters: List<ContentChapter> by lazy { mergeChapters() }

    val chapters: Map<String?, List<ContentChapter>> by lazy {
        allChapters.groupBy { it.branch }
    }

    val isLocal
        get() = manga.isLocal

    val local: LocalContent?
        get() = localContent ?: if (manga.isLocal) LocalContent(manga) else null

    val coverUrl: String?
        get() = override?.coverUrl
            .ifNullOrEmpty { manga.largeCoverUrl }
            .ifNullOrEmpty { manga.coverUrl }
            .ifNullOrEmpty { localContent?.manga?.coverUrl }
            ?.takeIfUsableImageUri()

    val isRestricted: Boolean
        get() = manga.state == ContentState.RESTRICTED

    private val mergedContent by lazy {
        if (localContent == null) {
            // 对于非本地漫画，也需要包含 chapters 信息
            manga.withOverride(override).copy(
                chapters = allChapters,
            )
        } else {
            val resolvedCoverUrl = override?.coverUrl.ifNullOrEmpty { manga.coverUrl }
            val resolvedLargeCoverUrl = override?.coverUrl.ifNullOrEmpty { manga.largeCoverUrl }
            manga.copy(
                title = override?.title.ifNullOrEmpty { manga.title },
                coverUrl = resolvedCoverUrl?.takeIfUsableImageUri().orEmpty(),
                largeCoverUrl = resolvedLargeCoverUrl?.takeIfUsableImageUri(),
                contentRating = override?.contentRating ?: manga.contentRating,
                chapters = allChapters,
            )
        }
    }

    fun toContent() = mergedContent

    fun getLocale(): Locale? {
        findAppropriateLocale(chapters.keys.singleOrNull())?.let {
            return it
        }
        return manga.source.getLocale()
    }

    fun filterChapters(branch: String?) = copy(
        manga = manga.filterChapters(branch),
        localContent = localContent?.run {
            copy(manga = manga.filterChapters(branch))
        },
    )

    private fun mergeChapters(): List<ContentChapter> {
        val chapters = manga.chapters
        val localChapters = local?.manga?.chapters.orEmpty()
        if (chapters.isNullOrEmpty()) {
            android.util.Log.d(
                "ContentDetails",
                "mergeChapters: remote empty, localCount=${localChapters.size}, branches=${localChapters.groupBy { it.branch }.mapValues { it.value.size }}",
            )
            return localChapters
        }
        android.util.Log.d(
            "ContentDetails",
            "mergeChapters: remoteCount=${chapters.size}, localCount=${localChapters.size}, remoteBranches=${chapters.groupBy { it.branch }.mapValues { it.value.size }}, localBranches=${localChapters.groupBy { it.branch }.mapValues { it.value.size }}",
        )
        val localMap = if (localChapters.isNotEmpty()) {
            localChapters.associateByTo(LinkedHashMap(localChapters.size)) { it.id }
        } else {
            null
        }
        val remainingLocalChapters = localMap?.values?.toMutableList() ?: mutableListOf()
        val result = ArrayList<ContentChapter>(chapters.size)
        
        for (chapter in chapters) {
            var local = localMap?.remove(chapter.id)
            if (local != null) {
                remainingLocalChapters.remove(local)
            } else {
                // Fallback: match by number and title
                val match = remainingLocalChapters.find { 
                    it.number == chapter.number && it.title == chapter.title
                } ?: remainingLocalChapters.find {
                    it.number == chapter.number && chapter.number > 0f
                } ?: remainingLocalChapters.find {
                    it.title == chapter.title && !it.title.isNullOrBlank()
                }
                
                if (match != null) {
                    local = match
                    remainingLocalChapters.remove(match)
                    localMap?.remove(match.id)
                }
            }
            result += if (local != null) {
                local.copy(branch = chapter.branch)
            } else {
                chapter
            }
        }
        
        if (remainingLocalChapters.isNotEmpty()) {
            result.addAll(remainingLocalChapters)
        }
        android.util.Log.d(
            "ContentDetails",
            "mergeChapters: resultCount=${result.size}, resultBranches=${result.groupBy { it.branch }.mapValues { it.value.size }}, first=${result.take(3).map { "${it.id}|${it.branch}|${it.title}" }}",
        )
        return result
    }

    private fun findAppropriateLocale(name: String?): Locale? {
        if (name.isNullOrEmpty()) {
            return null
        }
        return Locale.getAvailableLocales().find { lc ->
            name.contains(lc.getDisplayName(lc), ignoreCase = true) ||
                name.contains(lc.getDisplayName(Locale.ENGLISH), ignoreCase = true) ||
                name.contains(lc.getDisplayLanguage(lc), ignoreCase = true) ||
                name.contains(lc.getDisplayLanguage(Locale.ENGLISH), ignoreCase = true)
        }
    }
}
