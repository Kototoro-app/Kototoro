package org.skepsun.kototoro.source.host

import org.skepsun.kototoro.core.source.MihonModelRules
import org.skepsun.kototoro.core.source.SourceChapter
import org.skepsun.kototoro.core.source.SourceContent
import org.skepsun.kototoro.core.source.SourceExternalTrack
import org.skepsun.kototoro.core.source.SourcePage
import org.skepsun.kototoro.core.source.SourceRef
import org.skepsun.kototoro.core.source.SourceTag
import kotlin.coroutines.cancellation.CancellationException

/** Aniyomi's `SAnime` / `SEpisode` / `Video` as portable models, by reflection over the host's animesource ABI. */
internal class AniyomiModelAdapter(private val lease: MihonJarRegistry.SourceLease) {
    private val source: SourceRef = lease.descriptor.source
    private val baseUrl get() = read(lease.instance, "getBaseUrl") as? String ?: ""

    fun content(anime: Any, episodes: List<SourceChapter>? = null): SourceContent {
        val url = read(anime, "getUrl") as? String ?: ""
        val title = read(anime, "getTitle") as? String ?: "Unknown"
        val cover = MihonModelRules.resolveUrl(baseUrl, read(anime, "getThumbnail_url") as? String)
        val genres = (read(anime, "getGenres") as? List<*>)?.filterIsInstance<String>().orEmpty()
        return SourceContent(
            id = MihonModelRules.contentId(url, source.name, title), title = title, altTitles = emptySet(), url = url,
            publicUrl = publicUrl(anime)?.takeIf(String::isNotBlank) ?: MihonModelRules.resolveUrl(baseUrl, url).orEmpty(),
            rating = -1f,
            contentRating = MihonModelRules.contentRating(source.contentType.startsWith("HENTAI_"), null, genres),
            coverUrl = cover,
            tags = genres.mapNotNull { genre ->
                MihonModelRules.cleanGenre(genre).takeIf(String::isNotEmpty)?.let {
                    SourceTag(it, it.lowercase().replace(" ", "_"), source)
                }
            }.toSet(),
            state = when ((read(anime, "getStatus") as? Number)?.toInt()) {
                1 -> "ONGOING"; 2, 4 -> "FINISHED"; 3 -> "RESTRICTED"; 5 -> "ABANDONED"; 6 -> "PAUSED"; else -> null
            },
            authors = listOfNotNull(read(anime, "getAuthor") as? String, read(anime, "getArtist") as? String)
                .filter(String::isNotBlank).toSet(),
            source = source,
            largeCoverUrl = MihonModelRules.resolveUrl(baseUrl, read(anime, "getBackground_url") as? String) ?: cover,
            description = read(anime, "getDescription") as? String, chapters = episodes,
        )
    }

    fun anime(content: SourceContent): Any = create("SAnimeImpl").also {
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
        write(it, "setBackground_url", content.largeCoverUrl?.takeIf { large -> large != content.coverUrl })
        write(it, "setInitialized", true)
    }

    /** The extension may mutate what it is given, so every call gets its own copy. */
    fun copy(anime: Any): Any = create("SAnimeImpl").also {
        for (field in listOf("Url", "Title", "Artist", "Author", "Description", "Genre", "Status", "Thumbnail_url",
            "Background_url", "Update_strategy", "Fetch_type", "Season_number", "Initialized")) {
            val value = read(anime, "get$field") ?: continue
            write(it, "set$field", value)
        }
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
    }

    fun episode(episode: Any, parentUrl: String, number: Float): SourceChapter {
        val url = read(episode, "getUrl") as String
        val scanlator = (read(episode, "getScanlator") as? String)?.takeIf(String::isNotBlank)
        return SourceChapter(
            id = MihonModelRules.chapterId(url, source.name, parentUrl),
            title = (read(episode, "getName") as? String)?.takeIf(String::isNotBlank),
            number = number, volume = 0, url = url, scanlator = scanlator,
            uploadDate = (read(episode, "getDate_upload") as? Long) ?: 0, branch = scanlator, source = source,
        )
    }

    fun episode(chapter: SourceChapter): Any = create("SEpisodeImpl").also {
        write(it, "setUrl", chapter.url)
        write(it, "setName", chapter.title ?: "Episode ${chapter.number}")
        write(it, "setEpisode_number", chapter.number)
        write(it, "setDate_upload", chapter.uploadDate)
        write(it, "setScanlator", chapter.scanlator)
    }

    fun copyEpisode(episode: Any): Any = create("SEpisodeImpl").also {
        for (field in listOf("Url", "Name", "Date_upload", "Episode_number", "Fillermark", "Scanlator", "Summary", "Preview_url")) {
            val value = read(episode, "get$field") ?: read(episode, "is$field") ?: continue
            write(it, "set$field", value)
        }
    }

    /** One playable stream: the video's own headers, subtitle and audio tracks travel with the page. */
    fun page(video: Any, chapter: SourceChapter, index: Int): SourcePage? {
        val url = (read(video, "getVideoUrl") as? String)?.takeIf { it.isNotBlank() && it != "null" } ?: return null
        val headers = headers(read(video, "getHeaders"))
        return SourcePage(
            id = MihonModelRules.pageId(chapter.id.toString(), index), url = url, preview = null, source = source,
            headers = headers.takeIf { it.isNotEmpty() },
            externalSubtitleTracks = tracks(read(video, "getSubtitleTracks"), headers),
            externalAudioTracks = tracks(read(video, "getAudioTracks"), headers),
            playbackLabel = (read(video, "getVideoTitle") as? String)?.takeIf(String::isNotBlank),
            playbackQuality = (read(video, "getResolution") as? Number)?.toInt(),
        )
    }

    fun preferred(video: Any): Boolean = read(video, "getPreferred") as? Boolean ?: false

    private fun tracks(value: Any?, headers: Map<String, String>): List<SourceExternalTrack> =
        (value as? List<*>).orEmpty().mapNotNull { track ->
            val url = read(track ?: return@mapNotNull null, "getUrl") as? String ?: return@mapNotNull null
            SourceExternalTrack(url, read(track, "getLang") as? String ?: "", headers.takeIf { it.isNotEmpty() })
        }

    private fun headers(value: Any?): Map<String, String> {
        if (value == null) return emptyMap()
        val size = MihonReflection.call(value, "size") as Int
        return buildMap {
            for (index in 0 until size) {
                put(MihonReflection.call(value, "name", index) as String, MihonReflection.call(value, "value", index) as String)
            }
        }
    }

    private fun publicUrl(anime: Any): String? = try {
        read(lease.instance, "getAnimeUrl", anime) as? String
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        // An optional public URL formatter must not discard an otherwise valid anime.
        null
    }

    private fun create(name: String) = MihonReflection.createIn(lease.loader, NativeAbi.ANIYOMI.modelPackage, name)

    companion object {
        fun read(target: Any, name: String, vararg arguments: Any?) = MihonReflection.optional(target, name, *arguments)
        private fun write(target: Any, name: String, value: Any?) { MihonReflection.optional(target, name, value) }
    }
}
