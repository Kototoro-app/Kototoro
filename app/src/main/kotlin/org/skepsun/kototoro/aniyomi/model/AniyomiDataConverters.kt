package org.skepsun.kototoro.aniyomi.model

import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import org.skepsun.kototoro.core.model.isAdultTagKeyword
import org.skepsun.kototoro.parsers.model.ContentRating
import org.skepsun.kototoro.parsers.model.Content
import org.skepsun.kototoro.parsers.model.ContentChapter
import org.skepsun.kototoro.parsers.model.ContentPage
import org.skepsun.kototoro.parsers.model.ContentSource
import org.skepsun.kototoro.parsers.model.ContentState
import org.skepsun.kototoro.parsers.model.ContentTag
import org.skepsun.kototoro.parsers.model.RATING_UNKNOWN

/**
 * Extension functions for converting between Aniyomi and Kototoro data models.
 */

// ============ SAnime <-> Content ============

fun SAnime.toKotoContent(
    source: AniyomiAnimeSource,
    chapters: List<ContentChapter>? = null,
    publicUrl: String = "",
): Content {
    val baseUrl = (source.animeCatalogueSource as? AnimeHttpSource)?.baseUrl ?: ""

    val safeUrl = try { url } catch (e: Exception) { "" }
    val safeThumbnail = try { thumbnail_url } catch (e: Exception) { null }
    val absoluteThumbnailUrl = resolveUrl(baseUrl, safeThumbnail)
    val absolutePublicUrl = resolveUrl(baseUrl, safeUrl) ?: safeUrl

    val safeTitle = try { title } catch (e: Exception) { "Unknown" }
    val safeGenres = try { getGenres() } catch (e: Exception) { null }
    val safeAuthor = try { author } catch (e: Exception) { null }
    val safeArtist = try { artist } catch (e: Exception) { null }
    val safeDescription = try { description } catch (e: Exception) { null }
    val safeStatus = try { status } catch (e: Exception) { SAnime.UNKNOWN }

    return Content(
        id = generateId(safeUrl, source.name, "manga"),
        title = safeTitle,
        altTitles = emptySet(),
        url = safeUrl,
        publicUrl = if (publicUrl.isNotBlank()) publicUrl else absolutePublicUrl,
        rating = RATING_UNKNOWN,
        contentRating = run {
            val isNsfw = source.isNsfw || safeGenres?.any { it.isAdultTagKeyword() } == true
            if (isNsfw) ContentRating.ADULT else null
        },
        coverUrl = absoluteThumbnailUrl,
        largeCoverUrl = absoluteThumbnailUrl,
        tags = safeGenres?.map { genreName ->
            ContentTag(
                title = genreName,
                key = genreName.lowercase().replace(" ", "_"),
                source = source,
            )
        }?.toSet() ?: emptySet(),
        state = when (safeStatus) {
            SAnime.ONGOING -> ContentState.ONGOING
            SAnime.COMPLETED -> ContentState.FINISHED
            SAnime.ON_HIATUS -> ContentState.PAUSED
            SAnime.CANCELLED -> ContentState.ABANDONED
            SAnime.LICENSED -> ContentState.RESTRICTED
            SAnime.PUBLISHING_FINISHED -> ContentState.FINISHED
            else -> null
        },
        authors = buildSet {
            safeAuthor?.takeIf { it.isNotBlank() }?.let { add(it) }
            safeArtist?.takeIf { it.isNotBlank() && it != safeAuthor }?.let { add(it) }
        },
        description = safeDescription,
        chapters = chapters,
        source = source,
    )
}

fun Content.toAniyomiAnime(): SAnime {
    val baseUrl = (source as? AniyomiAnimeSource)?.let {
        (it.animeCatalogueSource as? AnimeHttpSource)?.baseUrl ?: ""
    } ?: ""

    var cleanUrl = url
    // Handle duplicate baseUrl and fix protocols as in MihonDataConverters
    cleanUrl = cleanUrl.replace(Regex("^(https?)/+"), "$1://")
    if (baseUrl.isNotBlank() && cleanUrl.startsWith(baseUrl.trimEnd('/'))) {
        cleanUrl = cleanUrl.substring(baseUrl.trimEnd('/').length)
    }

    return SAnime.create().apply {
        this.url = cleanUrl
        this.title = this@toAniyomiAnime.title
        this.author = this@toAniyomiAnime.authors.firstOrNull()
        this.artist = this@toAniyomiAnime.authors.drop(1).firstOrNull()
        this.description = this@toAniyomiAnime.description
        this.genre = this@toAniyomiAnime.tags.joinToString(", ") { it.title }
        this.status = when (this@toAniyomiAnime.state) {
            ContentState.ONGOING -> SAnime.ONGOING
            ContentState.FINISHED -> SAnime.COMPLETED
            ContentState.PAUSED -> SAnime.ON_HIATUS
            ContentState.ABANDONED -> SAnime.CANCELLED
            ContentState.RESTRICTED -> SAnime.LICENSED
            else -> SAnime.UNKNOWN
        }
        this.thumbnail_url = this@toAniyomiAnime.coverUrl
        this.initialized = true
    }
}

// ============ SEpisode <-> ContentChapter ============

fun SEpisode.toKotoChapter(source: ContentSource, overrideNumber: Float? = null): ContentChapter {
    val chapterNumber = overrideNumber ?: (if (episode_number >= 0) episode_number else 0f)
    return ContentChapter(
        id = generateId(url, source.name, "episode"),
        title = name.takeIf { it.isNotBlank() },
        number = chapterNumber,
        volume = 0,
        url = url,
        scanlator = scanlator,
        uploadDate = date_upload,
        branch = scanlator,
        source = source,
    )
}

fun ContentChapter.toAniyomiEpisode(): SEpisode {
    return SEpisode.create().apply {
        this.url = this@toAniyomiEpisode.url
        this.name = this@toAniyomiEpisode.title ?: "Episode ${this@toAniyomiEpisode.number}"
        this.episode_number = this@toAniyomiEpisode.number
        this.date_upload = this@toAniyomiEpisode.uploadDate
        this.scanlator = this@toAniyomiEpisode.scanlator
    }
}

// ============ Video <-> ContentPage ============

fun Video.toKotoPage(
    source: ContentSource,
    episode: SEpisode,
    index: Int
): ContentPage {
    val pageId = "${episode.url}|video|$index".hashCode().toLong() and Long.MAX_VALUE

    // Convert Headers to Map
    val headerMap = mutableMapOf<String, String>()
    headers?.let { h ->
        for (i in 0 until h.size) {
            headerMap[h.name(i)] = h.value(i)
        }
    }

    return ContentPage(
        id = pageId,
        url = videoUrl ?: "",
        preview = null,
        headers = headerMap,
        source = source,
    )
}

// ============ ID Generation ============

private fun generateId(url: String, sourceName: String, type: String): Long {
    return "$sourceName|$type|$url".hashCode().toLong() and Long.MAX_VALUE
}

// ============ URL Helpers ============

fun AnimeHttpSource.getPublicAnimeUrl(anime: SAnime): String {
    return try {
        getAnimeUrl(anime)
    } catch (e: Exception) {
        ""
    }
}

private fun resolveUrl(baseUrl: String, url: String?): String? {
    if (url.isNullOrBlank()) return null
    if (url.startsWith("http")) return url
    if (url.startsWith("//")) return "https:$url"
    if (baseUrl.isNotBlank()) {
        return baseUrl.trimEnd('/') + "/" + url.trimStart('/')
    }
    return url
}
