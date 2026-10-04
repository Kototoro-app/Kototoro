package org.skepsun.kototoro.source.host

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.skepsun.kototoro.core.source.MihonModelRules
import org.skepsun.kototoro.core.source.SourceChapter
import org.skepsun.kototoro.core.source.SourceContent
import org.skepsun.kototoro.core.source.SourceRef
import org.skepsun.kototoro.core.source.SourceTag
import kotlin.coroutines.cancellation.CancellationException

internal class MihonModelAdapter(private val lease: MihonJarRegistry.SourceLease) {
    private val source: SourceRef = lease.descriptor.source
    private val baseUrl get() = read(lease.instance, "getBaseUrl") as? String ?: ""

    fun content(manga: Any, chapters: List<SourceChapter>? = null): SourceContent {
        val url = read(manga, "getUrl") as? String ?: ""
        val title = read(manga, "getTitle") as? String ?: "Unknown"
        val cover = MihonModelRules.resolveUrl(baseUrl, read(manga, "getThumbnail_url") as? String)
        val genres = (read(manga, "getGenres") as? List<*>)?.filterIsInstance<String>()
            ?: (read(manga, "getGenre") as? String)?.split(',')?.map(String::trim).orEmpty()
        val score = (read(manga, "getScore") as? Number)?.toFloat()
        val explicit = read(manga, "getContentRating")?.toString()?.takeIf { it in setOf("SAFE", "SUGGESTIVE", "ADULT") }
        val rating = MihonModelRules.contentRating(source.contentType.startsWith("HENTAI_"), explicit, genres)
        return SourceContent(
            id = MihonModelRules.contentId(url, source.name, title), title = title,
            altTitles = (read(manga, "getAltTitles") as? List<*>)?.filterIsInstance<String>()?.toSet().orEmpty(),
            url = url,
            publicUrl = publicUrl(manga)?.takeIf(String::isNotBlank)
                ?: MihonModelRules.resolveUrl(baseUrl, url).orEmpty(),
            rating = when { score == null || score <= 0 || score > 100 -> -1f
                score <= 10 -> score / 10f; else -> score / 100f },
            contentRating = rating, coverUrl = cover,
            tags = genres.mapNotNull { genre ->
                MihonModelRules.cleanGenre(genre).takeIf(String::isNotEmpty)?.let {
                    SourceTag(it, it.lowercase().replace(" ", "_"), source)
                }
            }.toSet(),
            state = when ((read(manga, "getStatus") as? Number)?.toInt()) {
                1 -> "ONGOING"; 2, 4 -> "FINISHED"; 3 -> "RESTRICTED"; 5 -> "ABANDONED"; 6 -> "PAUSED"; else -> null
            },
            authors = listOfNotNull(read(manga, "getAuthor") as? String, read(manga, "getArtist") as? String)
                .filter(String::isNotBlank).toSet(),
            source = source, largeCoverUrl = MihonModelRules.resolveUrl(baseUrl, read(manga, "getBanner") as? String) ?: cover,
            description = read(manga, "getDescription") as? String, chapters = chapters, sourceData = memo(manga),
        )
    }

    fun manga(content: SourceContent): Any = MihonReflection.create(lease.loader, "SMangaImpl").also {
        write(it, "setUrl", MihonModelRules.mangaUrl(baseUrl, content.url))
        write(it, "setTitle", content.title)
        write(it, "setAuthor", content.authors.firstOrNull())
        write(it, "setArtist", content.authors.drop(1).firstOrNull())
        write(it, "setDescription", content.description)
        write(it, "setGenre", content.tags.joinToString(", ") { tag -> tag.title })
        write(it, "setStatus", when (content.state) {
            "ONGOING" -> 1; "FINISHED" -> 2; "RESTRICTED" -> 3; "ABANDONED" -> 5; "PAUSED" -> 6; else -> 0
        })
        write(it, "setThumbnail_url", content.coverUrl)
        write(it, "setInitialized", true)
        restoreMemo(it, content.sourceData)
        write(it, "setGenres", content.tags.map { tag -> tag.title })
        write(it, "setAltTitles", content.altTitles.toList())
        write(it, "setBanner", content.largeCoverUrl)
    }

    fun chapter(chapter: Any, parentUrl: String, number: Float): SourceChapter {
        val url = read(chapter, "getUrl") as String
        val scanlators = (read(chapter, "getScanlators") as? List<*>)?.filterIsInstance<String>()
        val scanlator = scanlators?.takeIf(List<String>::isNotEmpty)?.joinToString(", ")
            ?: read(chapter, "getScanlator") as? String
        return SourceChapter(
            id = MihonModelRules.chapterId(url, source.name, parentUrl),
            title = (read(chapter, "getName") as? String)?.takeIf(String::isNotBlank),
            number = number, volume = (read(chapter, "getVolume") as? String)?.toIntOrNull() ?: 0,
            url = url, scanlator = scanlator, uploadDate = (read(chapter, "getDate_upload") as? Long) ?: 0,
            branch = scanlator, source = source, sourceData = memo(chapter),
        )
    }

    fun chapter(chapter: SourceChapter): Any = MihonReflection.create(lease.loader, "SChapterImpl").also {
        write(it, "setUrl", chapter.url)
        write(it, "setName", chapter.title ?: "Chapter ${chapter.number}")
        write(it, "setChapter_number", chapter.number)
        write(it, "setDate_upload", chapter.uploadDate)
        write(it, "setScanlator", chapter.scanlator)
        restoreMemo(it, chapter.sourceData)
        write(it, "setNumber", chapter.number.toString())
        write(it, "setVolume", chapter.volume.takeIf { value -> value > 0 }?.toString())
        write(it, "setScanlators", chapter.scanlator?.let(::listOf).orEmpty())
    }

    fun detailFallbacks(details: Any, original: Any) {
        val url = read(original, "getUrl") as? String ?: ""
        write(details, "setUrl", url)
        if ((read(details, "getTitle") as? String).isNullOrBlank()) {
            write(details, "setTitle", (read(original, "getTitle") as? String)?.ifBlank { "Unknown" } ?: "Unknown")
        }
        val thumbnail = read(details, "getThumbnail_url") as? String
        if (thumbnail.isNullOrBlank() || thumbnail == url) {
            (read(original, "getThumbnail_url") as? String)?.takeIf(String::isNotBlank)
                ?.let { write(details, "setThumbnail_url", it) }
        }
        if (memo(details) == null) restoreMemo(details, memo(original))
    }

    private fun memo(model: Any): String? = (read(model, "getMemo") as? JsonObject)?.takeIf { it.isNotEmpty() }?.toString()
    private fun publicUrl(manga: Any): String? = try {
        read(lease.instance, "getMangaUrl", manga) as? String
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        // Match Android: an optional public URL formatter must not discard an otherwise valid manga.
        null
    }
    private fun restoreMemo(model: Any, data: String?) {
        if (data == null) return
        val memo = try { Json.parseToJsonElement(data) as? JsonObject } catch (_: IllegalArgumentException) { null }
        if (memo != null) write(model, "setMemo", memo)
    }

    companion object {
        fun read(target: Any, name: String, vararg arguments: Any?) = MihonReflection.optional(target, name, *arguments)
        private fun write(target: Any, name: String, value: Any?) { MihonReflection.optional(target, name, value) }
    }
}
