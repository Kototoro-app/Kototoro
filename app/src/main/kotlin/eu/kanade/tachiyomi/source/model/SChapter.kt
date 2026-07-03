@file:Suppress("PropertyName")

package eu.kanade.tachiyomi.source.model

import java.io.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * Mihon-compatible SChapter interface.
 * Ported from Mihon source-api for extension compatibility.
 */
interface SChapter : Serializable {

    var url: String

    var name: String

    var date_upload: Long

    var chapter_number: Float

    var scanlator: String?

    // TachiyomiX 1.6+ additions with default implementations for backward compatibility
    var number: String?
        get() = if (chapter_number >= 0) chapter_number.toString() else null
        set(value) {
            chapter_number = value?.toFloatOrNull() ?: -1f
        }

    var volume: String?
        get() = null
        set(value) {}

    var scanlators: List<String>
        get() = scanlator?.let { listOf(it) } ?: emptyList()
        set(value) {
            scanlator = value.joinToString(", ")
        }

    var note: String?
        get() = null
        set(value) {}

    var memo: JsonObject
        get() = JsonObject(emptyMap())
        set(value) {}

    fun copyFrom(other: SChapter) {
        name = other.name
        url = other.url
        date_upload = other.date_upload
        chapter_number = other.chapter_number
        scanlator = other.scanlator
        try {
            number = other.number
            volume = other.volume
            scanlators = other.scanlators
            note = other.note
            memo = other.memo
        } catch (e: NoSuchMethodError) {
            // Fallback for compatibility
        }
    }

    companion object {
        fun create(): SChapter {
            return SChapterImpl()
        }
    }
}

/**
 * Default implementation of SChapter.
 */
class SChapterImpl : SChapter {

    override lateinit var url: String

    override lateinit var name: String

    override var date_upload: Long = 0

    override var chapter_number: Float = -1f

    override var scanlator: String? = null

    // Overridden fields in default implementation to back the new properties
    private var _number: String? = null
    override var number: String?
        get() {
            val local = _number
            if (local != null) return local
            return if (chapter_number >= 0) chapter_number.toString() else null
        }
        set(value) {
            _number = value
            val parsed = value?.toFloatOrNullCompat()
            if (parsed != null) {
                chapter_number = parsed
            }
        }

    override var volume: String? = null

    private var _scanlators: List<String>? = null
    override var scanlators: List<String>
        get() {
            val local = _scanlators
            if (local != null) return local
            return scanlator?.let { listOf(it) } ?: emptyList()
        }
        set(value) {
            _scanlators = value
            scanlator = value.joinToString(", ")
        }

    override var note: String? = null
    override var memo: JsonObject = JsonObject(emptyMap())
}

/**
 * Compatible helper function to parse float from string chapter number (e.g. "10.5a" -> 10.5f).
 */
fun String.toFloatOrNullCompat(): Float? {
    this.toFloatOrNull()?.let { return it }
    val clean = this.trim().replace(Regex("[a-zA-Z]+$"), "")
    return clean.toFloatOrNull()
}
