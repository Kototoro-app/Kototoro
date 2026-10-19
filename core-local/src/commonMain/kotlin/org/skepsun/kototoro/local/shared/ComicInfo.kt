package org.skepsun.kototoro.local.shared

/**
 * The parts of an Anansi `ComicInfo.xml` (the metadata Mihon and its forks write into local CBZ archives and manga
 * folders) that a library uses. List fields are comma-separated in the file. Mihon also writes its publishing
 * status as a namespaced `PublishingStatusTachiyomi` element, read here as [status].
 */
data class ComicInfo(
    val series: String? = null,
    val title: String? = null,
    val number: String? = null,
    val volume: Int? = null,
    val summary: String? = null,
    val writers: List<String> = emptyList(),
    val artists: List<String> = emptyList(),
    val genres: List<String> = emptyList(),
    val tags: List<String> = emptyList(),
    val web: String? = null,
    val languageIso: String? = null,
    val ageRating: String? = null,
    val status: Status? = null,
) {
    enum class Status { ONGOING, FINISHED, ABANDONED, PAUSED }

    /** Writers first, then the art credits, each once. */
    val authors: List<String> get() = (writers + artists).distinctBy { it.lowercase() }

    /** Genres then free tags, each once. */
    val allTags: List<String> get() = (genres + tags).distinctBy { it.lowercase() }

    /** True for the ratings ComicInfo reserves for adults. */
    val isAdult: Boolean get() = ageRating?.trim()?.lowercase() in ADULT_RATINGS

    /** The chapter number as a float, when the archive is one chapter. */
    val chapterNumber: Float? get() = number?.trim()?.toFloatOrNull()

    companion object {
        const val FILE_NAME = "ComicInfo.xml"

        private val ADULT_RATINGS = setOf("adults only 18+", "r18+", "x18+", "mature 17+")
        private const val ROOT = "ComicInfo"

        /** Null when [xml] is not a ComicInfo document or carries none of the fields above. */
        fun parse(xml: String): ComicInfo? {
            if (!Regex("<(?:[\\w.-]+:)?$ROOT[\\s>/]", RegexOption.IGNORE_CASE).containsMatchIn(xml)) return null
            fun text(name: String) = LocalXml.texts(xml, name).firstOrNull()?.takeIf(String::isNotBlank)
            fun list(vararg names: String) = names.flatMap { name -> text(name)?.split(',', ';').orEmpty() }
                .map(String::trim).filter(String::isNotEmpty).distinctBy { it.lowercase() }
            val info = ComicInfo(
                series = text("Series"),
                title = text("Title"),
                number = text("Number"),
                volume = text("Volume")?.toIntOrNull(),
                summary = text("Summary"),
                writers = list("Writer"),
                artists = list("Penciller", "Inker", "Colorist", "Letterer", "CoverArtist"),
                genres = list("Genre"),
                tags = list("Tags"),
                web = text("Web")?.split(' ')?.firstOrNull { it.startsWith("http") },
                languageIso = text("LanguageISO"),
                ageRating = text("AgeRating"),
                status = text("PublishingStatusTachiyomi")?.let(::status),
            )
            return info.takeUnless { it == ComicInfo() }
        }

        private fun status(value: String): Status? = when (value.trim().lowercase()) {
            "ongoing" -> Status.ONGOING
            "completed", "publishing finished" -> Status.FINISHED
            "cancelled" -> Status.ABANDONED
            "on hiatus" -> Status.PAUSED
            else -> null
        }
    }
}
