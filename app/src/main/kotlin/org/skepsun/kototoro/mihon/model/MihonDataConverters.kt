package org.skepsun.kototoro.mihon.model

import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.toFloatOrNullCompat
import eu.kanade.tachiyomi.source.online.HttpSource
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.skepsun.kototoro.core.source.MihonModelRules
import org.skepsun.kototoro.parsers.model.ContentRating
import org.skepsun.kototoro.parsers.model.Content
import org.skepsun.kototoro.parsers.model.ContentChapter
import org.skepsun.kototoro.parsers.model.ContentPage
import org.skepsun.kototoro.parsers.model.ContentSource
import org.skepsun.kototoro.parsers.model.ContentState
import org.skepsun.kototoro.parsers.model.ContentTag
import org.skepsun.kototoro.parsers.model.RATING_UNKNOWN

/**
 * Extension functions for converting between Mihon and Kototoro data models.
 */

// ============ SManga <-> Content ============

/**
 * Convert Mihon SManga to Kototoro Content.
 */
fun SManga.toKotoContent(
    source: MihonMangaSource,
    chapters: List<ContentChapter>? = null,
    publicUrl: String = "",
): Content {
    val safeMemo = runCatching { memo }.getOrDefault(JsonObject(emptyMap()))
    // Get baseUrl from source if available to resolve relative URLs
    val baseUrl = (source.catalogueSource as? HttpSource)?.baseUrl ?: ""

    val safeUrl = try { url } catch (e: UninitializedPropertyAccessException) { "" }
    val safeThumbnail = try { thumbnail_url } catch (e: UninitializedPropertyAccessException) { null }
    val absoluteThumbnailUrl = resolveUrl(baseUrl, safeThumbnail)
    val absolutePublicUrl = resolveUrl(baseUrl, safeUrl) ?: safeUrl
    val stableUrl = safeUrl.ifBlank { absolutePublicUrl }

    // Safely access lateinit properties
    val safeTitle = try { title } catch (e: UninitializedPropertyAccessException) { "Unknown" }

    // Try accessing genres property, which falls back to legacy genre property internally
    val safeGenres = try {
        genres
    } catch (e: Exception) {
        null
    }

    val safeAuthor = try { author } catch (e: UninitializedPropertyAccessException) { null }
    val safeArtist = try { artist } catch (e: UninitializedPropertyAccessException) { null }
    val safeDescription = try { description } catch (e: UninitializedPropertyAccessException) { null }
    val safeStatus = try { status } catch (e: UninitializedPropertyAccessException) { SManga.UNKNOWN }

    // Try accessing altTitles
    val safeAltTitles = try {
        altTitles.toSet()
    } catch (e: NoSuchMethodError) {
        emptySet()
    }

    // Try accessing banner cover
    val safeBanner = try {
        banner?.let { resolveUrl(baseUrl, it) }
    } catch (e: NoSuchMethodError) {
        null
    }

    // Parse rating from score
    val calculatedRating = try {
        val safeScore = score
        if (safeScore != null && safeScore > 0) {
            if (safeScore <= 10) safeScore / 10f
            else if (safeScore <= 100) safeScore / 100f
            else RATING_UNKNOWN
        } else {
            RATING_UNKNOWN
        }
    } catch (e: NoSuchMethodError) {
        RATING_UNKNOWN
    }

    val generatedId = generateContentId(stableUrl, source.name, safeTitle)

    return Content(
        id = generatedId,
        title = safeTitle,
        altTitles = safeAltTitles,
        url = stableUrl,
        publicUrl = if (publicUrl.isNotBlank()) publicUrl else absolutePublicUrl,
        rating = calculatedRating,
        contentRating = run {
            val explicitRating = try {
                when (contentRating) {
                    SManga.ContentRating.SAFE -> ContentRating.SAFE
                    SManga.ContentRating.SUGGESTIVE -> ContentRating.SUGGESTIVE
                    SManga.ContentRating.ADULT -> ContentRating.ADULT
                    else -> null
                }
            } catch (e: NoSuchMethodError) {
                null
            }

            MihonModelRules.contentRating(source.isNsfw, explicitRating?.name, safeGenres.orEmpty())
                ?.let(ContentRating::valueOf)
        },
        coverUrl = absoluteThumbnailUrl,
        largeCoverUrl = safeBanner ?: absoluteThumbnailUrl,
        tags = safeGenres?.mapNotNull { genreName: String ->
            val clean = genreName.cleanMihonGenre()
            if (clean.isEmpty()) null
            else ContentTag(
                title = clean,
                key = clean.lowercase().replace(" ", "_"),
                source = source,
            )
        }?.toSet() ?: emptySet(),
        state = when (safeStatus) {
            SManga.ONGOING -> ContentState.ONGOING
            SManga.COMPLETED -> ContentState.FINISHED
            SManga.ON_HIATUS -> ContentState.PAUSED
            SManga.CANCELLED -> ContentState.ABANDONED
            SManga.LICENSED -> ContentState.RESTRICTED
            SManga.PUBLISHING_FINISHED -> ContentState.FINISHED
            else -> null
        },
        authors = buildSet {
            safeAuthor?.takeIf { it.isNotBlank() }?.let { add(it) }
            safeArtist?.takeIf { it.isNotBlank() && it != safeAuthor }?.let { add(it) }
        },
        description = safeDescription,
        chapters = chapters,
        source = source,
        sourceData = safeMemo.takeIf { it.isNotEmpty() }?.toString(),
    )
}

/**
 * Convert Kototoro Content to Mihon SManga (for calling Mihon APIs).
 */
fun Content.toMihonManga(): SManga {
    // Get baseUrl from source if available
    val baseUrl = (source as? MihonMangaSource)?.let { mihonSource ->
        (mihonSource.catalogueSource as? HttpSource)?.baseUrl ?: ""
    } ?: ""

    val cleanUrl = MihonModelRules.mangaUrl(baseUrl, url)
    android.util.Log.d("MihonDataConverters", "toMihonManga: original='$url' cleaned='$cleanUrl'")

    return SManga.create().apply {
        this.url = cleanUrl
        this.title = this@toMihonManga.title
        this.author = this@toMihonManga.authors.firstOrNull()
        this.artist = this@toMihonManga.authors.drop(1).firstOrNull()
        this.description = this@toMihonManga.description
        this.genre = this@toMihonManga.tags.joinToString(", ") { it.title }
        this.status = when (this@toMihonManga.state) {
            ContentState.ONGOING -> SManga.ONGOING
            ContentState.FINISHED -> SManga.COMPLETED
            ContentState.PAUSED -> SManga.ON_HIATUS
            ContentState.ABANDONED -> SManga.CANCELLED
            ContentState.RESTRICTED -> SManga.LICENSED
            else -> SManga.UNKNOWN
        }
        this.thumbnail_url = this@toMihonManga.coverUrl
        this.initialized = true
        this@toMihonManga.sourceData
            ?.let { sourceData -> runCatching { Json.parseToJsonElement(sourceData).jsonObject }.getOrNull() }
            ?.let { this.memo = it }
        try {
            this.genres = this@toMihonManga.tags.map { it.title }
            this.altTitles = this@toMihonManga.altTitles.toList()
            this.banner = this@toMihonManga.largeCoverUrl
            this.contentRating = when (this@toMihonManga.contentRating) {
                ContentRating.SAFE -> SManga.ContentRating.SAFE
                ContentRating.SUGGESTIVE -> SManga.ContentRating.SUGGESTIVE
                ContentRating.ADULT -> SManga.ContentRating.ADULT
                else -> SManga.ContentRating.SAFE
            }
        } catch (e: NoSuchMethodError) {
            // Fallback
        }
    }
}

// ============ SChapter <-> ContentChapter ============

/**
 * Convert Mihon SChapter to Kototoro ContentChapter.
 */
fun SChapter.toKotoChapter(
    source: ContentSource,
    overrideNumber: Float? = null,
    parentUrl: String? = null,
): ContentChapter {
    val safeMemo = runCatching { memo }.getOrDefault(JsonObject(emptyMap()))
    val chapterId = generateChapterId(url, source.name, parentUrl)
    val finalNumber = overrideNumber ?: try {
        number?.toFloatOrNullCompat() ?: (if (chapter_number >= 0) chapter_number else 0f)
    } catch (e: NoSuchMethodError) {
        if (chapter_number >= 0) chapter_number else 0f
    }

    val finalVolume = try {
        volume?.toIntOrNull() ?: 0
    } catch (e: NoSuchMethodError) {
        0
    }

    val finalScanlator = try {
        scanlators.takeIf { it.isNotEmpty() }?.joinToString(", ") ?: scanlator
    } catch (e: NoSuchMethodError) {
        scanlator
    }

    android.util.Log.d("MihonDataConverters", "toKotoChapter: name='$name' url='$url' -> id=$chapterId number=$finalNumber volume=$finalVolume")

    return ContentChapter(
        id = chapterId,
        title = name.takeIf { it.isNotBlank() },
        number = finalNumber,
        volume = finalVolume,
        url = url,
        scanlator = finalScanlator,
        uploadDate = date_upload,
        branch = finalScanlator, // Use scanlator as branch for grouping
        source = source,
        sourceData = safeMemo.takeIf { it.isNotEmpty() }?.toString(),
    )
}

/**
 * Convert Kototoro ContentChapter to Mihon SChapter.
 */
fun ContentChapter.toMihonChapter(): SChapter {
    return SChapter.create().apply {
        this.url = this@toMihonChapter.url
        this.name = this@toMihonChapter.title ?: "Chapter ${this@toMihonChapter.number}"
        this.chapter_number = this@toMihonChapter.number
        this.date_upload = this@toMihonChapter.uploadDate
        this.scanlator = this@toMihonChapter.scanlator
        try {
            this.number = this@toMihonChapter.number.toString()
            this.volume = this@toMihonChapter.volume.takeIf { it > 0 }?.toString()
            this.scanlators = this@toMihonChapter.scanlator?.let { listOf(it) } ?: emptyList()
        } catch (e: NoSuchMethodError) {
            // Fallback
        }
        this@toMihonChapter.sourceData
            ?.let { sourceData -> runCatching { Json.parseToJsonElement(sourceData).jsonObject }.getOrNull() }
            ?.let { this.memo = it }
    }
}

// ============ Page <-> ContentPage ============

/**
 * Convert Mihon Page to Kototoro ContentPage.
 *
 * NOTE: The chapter parameter is needed to generate unique page IDs.
 * Without it, all chapters would have pages with IDs 0, 1, 2... which causes
 * cache conflicts in the reader.
 */
fun Page.toKotoPage(
    source: ContentSource,
    chapter: eu.kanade.tachiyomi.source.model.SChapter,
    chapterId: Long? = null,
    headers: Map<String, String> = emptyMap(),
): ContentPage {
    // Generate a unique page ID by combining chapter URL and page index
    // This prevents cache collisions between pages from different chapters
    val pageId = MihonModelRules.pageId((chapterId ?: chapter.url).toString(), index)

    return ContentPage(
        id = pageId,
        url = imageUrl ?: url,
        preview = null,
        headers = headers,
        source = source,
    )
}

/**
 * Convert Kototoro ContentPage to Mihon Page.
 */
fun ContentPage.toMihonPage(): Page {
    return Page(
        index = id.toInt(),
        url = url,
        imageUrl = url.takeIf { it.isNotBlank() },
    )
}

// ============ ID Generation ============

/**
 * Generate a stable ID for a manga based on URL and source.
 */
private fun generateContentId(url: String, sourceName: String, title: String): Long {
    return MihonModelRules.contentId(url, sourceName, title)
}

/**
 * Generate a stable ID for a chapter based on URL and source.
 */
private fun generateChapterId(url: String, sourceName: String, parentUrl: String? = null): Long {
    return MihonModelRules.chapterId(url, sourceName, parentUrl)
}

// ============ URL Helpers ============

/**
 * Get the public URL for a manga from an HttpSource.
 */
fun HttpSource.getPublicContentUrl(manga: SManga): String {
    return try {
        getMangaUrl(manga)
    } catch (e: Exception) {
        ""
    }
}

/**
 * Get the public URL for a chapter from an HttpSource.
 */
fun HttpSource.getPublicChapterUrl(chapter: SChapter): String {
    return try {
        getChapterUrl(chapter)
    } catch (e: Exception) {
        ""
    }
}
/**
 * Resolve relative URL using baseUrl.
 */
private fun resolveUrl(baseUrl: String, url: String?): String? {
    return MihonModelRules.resolveUrl(baseUrl, url)
}

/**
 * Some Mihon sources (e.g. CopyManga) store data-class representations like
 * {@code ThemeInfo(name=爱情, pathWord=xiaoyuan)} inside SManga.genre, which
 * SManga.getGenres() then splits at commas into fragments.
 * Extract the first field value from such representations; discard fragments.
 */
private fun String.cleanMihonGenre(): String {
    return MihonModelRules.cleanGenre(this)
}
