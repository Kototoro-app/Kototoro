@file:Suppress("PropertyName")

package eu.kanade.tachiyomi.source.model

import java.io.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * Mihon-compatible SManga interface.
 * Ported from Mihon source-api for extension compatibility.
 */
interface SManga : Serializable {

    var url: String

    var title: String

    var artist: String?

    var author: String?

    var description: String?

    var genre: String?

    var status: Int

    var thumbnail_url: String?

    var update_strategy: UpdateStrategy

    var initialized: Boolean

    // TachiyomiX 1.6+ additions with default implementations for backward compatibility
    var genres: List<String>
        get() {
            val g = try { genre } catch (e: Exception) { null }
            if (g.isNullOrBlank()) return emptyList()
            return g.split(", ").map { it.trim() }.filterNot { it.isBlank() }.distinct()
        }
        set(value) {
            genre = value.joinToString(", ")
        }

    var altTitles: List<String>
        get() = emptyList()
        set(value) {}

    var banner: String?
        get() = null
        set(value) {}

    var contentRating: ContentRating
        get() = ContentRating.SAFE
        set(value) {}

    var score: Int?
        get() = null
        set(value) {}

    var readingMode: ReadingMode?
        get() = null
        set(value) {}

    var memo: JsonObject
        get() = JsonObject(emptyMap())
        set(value) {}

    fun copy() = create().also {
        it.url = url
        it.title = title
        it.artist = artist
        it.author = author
        it.description = description
        it.genre = genre
        it.status = status
        it.thumbnail_url = thumbnail_url
        it.update_strategy = update_strategy
        it.initialized = initialized
        try {
            it.genres = genres
            it.altTitles = altTitles
            it.banner = banner
            it.contentRating = contentRating
            it.score = score
            it.readingMode = readingMode
            it.memo = memo
        } catch (e: NoSuchMethodError) {
            // Fallback for bytecode/compatibility issues
        }
    }

    enum class ContentRating { SAFE, SUGGESTIVE, ADULT }
    enum class ReadingMode { RIGHT_TO_LEFT, LEFT_TO_RIGHT, LONG_STRIP }

    companion object {
        const val UNKNOWN = 0
        const val ONGOING = 1
        const val COMPLETED = 2
        const val LICENSED = 3
        const val PUBLISHING_FINISHED = 4
        const val CANCELLED = 5
        const val ON_HIATUS = 6

        fun create(): SManga {
            return SMangaImpl()
        }
    }
}

/**
 * Default implementation of SManga.
 */
class SMangaImpl : SManga {

    override lateinit var url: String

    override lateinit var title: String

    override var artist: String? = null

    override var author: String? = null

    override var description: String? = null

    override var genre: String? = null

    override var status: Int = 0

    override var thumbnail_url: String? = null

    override var update_strategy: UpdateStrategy = UpdateStrategy.ALWAYS_UPDATE

    override var initialized: Boolean = false

    // Overridden fields in default implementation to back the new properties
    private var _genres: List<String>? = null
    override var genres: List<String>
        get() {
            val local = _genres
            if (local != null) return local
            val g = try { genre } catch (e: Exception) { null }
            if (g.isNullOrBlank()) return emptyList()
            return g.split(", ").map { it.trim() }.filterNot { it.isBlank() }.distinct()
        }
        set(value) {
            _genres = value
            genre = value.joinToString(", ")
        }

    override var altTitles: List<String> = emptyList()
    override var banner: String? = null
    override var contentRating: SManga.ContentRating = SManga.ContentRating.SAFE
    override var score: Int? = null
    override var readingMode: SManga.ReadingMode? = null
    override var memo: JsonObject = JsonObject(emptyMap())
}
