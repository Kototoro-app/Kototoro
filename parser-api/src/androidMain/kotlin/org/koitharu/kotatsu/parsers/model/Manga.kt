package org.koitharu.kotatsu.parsers.model

import androidx.collection.ArrayMap
import org.koitharu.kotatsu.parsers.util.findById
import org.koitharu.kotatsu.parsers.util.nullIfEmpty

public data class Manga(
	/**
	 * Unique identifier for manga
	 */
	@JvmField public val id: Long,
	/**
	 * Manga title, human-readable
	 */
	@JvmField public val title: String,
	/**
	 * Alternative titles (for example on other language), may be empty
	 */
	@JvmField public val altTitles: Set<String>,
	/**
	 * Relative url to manga (**without** a domain) or any other uri.
	 * Used principally in parsers
	 */
	@JvmField public val url: String,
	/**
	 * Absolute url to manga, must be ready to open in browser
	 */
	@JvmField public val publicUrl: String,
	/**
	 * Normalized manga rating, must be in range of 0..1 or [RATING_UNKNOWN] if rating s unknown
	 * @see hasRating
	 */
	@JvmField public val rating: Float,
	/**
	 * Indicates that manga may contain sensitive information (18+, NSFW)
	 */
	@JvmField public val contentRating: ContentRating?,
	/**
	 * Absolute link to the cover
	 * @see largeCoverUrl
	 */
	@JvmField public val coverUrl: String?,
	/**
	 * Tags (genres) of the manga
	 */
	@JvmField public val tags: Set<MangaTag>,
	/**
	 * Manga status (ongoing, finished) or null if unknown
	 */
	@JvmField public val state: MangaState?,
	/**
	 * Authors of the manga
	 */
	@JvmField public val authors: Set<String>,
	/**
	 * Large cover url (absolute), null if is no large cover
	 * @see coverUrl
	 */
	@JvmField public val largeCoverUrl: String? = null,
	/**
	 * Manga description, may be html or null
	 */
	@JvmField public val description: String? = null,
	/**
	 * List of chapters
	 */
	@JvmField public val chapters: List<MangaChapter>? = null,
	/**
	 * Manga source
	 */
	@JvmField public val source: MangaSource,
) {

	@Deprecated("Use other constructor")
	public constructor(
		/**
		 * Unique identifier for manga
		 */
		id: Long,
		/**
		 * Manga title, human-readable
		 */
		title: String,
		/**
		 * Alternative title (for example on other language), may be null
		 */
		altTitle: String?,
		/**
		 * Relative url to manga (**without** a domain) or any other uri.
		 * Used principally in parsers
		 */
		url: String,
		/**
		 * Absolute url to manga, must be ready to open in browser
		 */
		publicUrl: String,
		/**
		 * Normalized manga rating, must be in range of 0..1 or [RATING_UNKNOWN] if rating s unknown
		 * @see hasRating
		 */
		rating: Float,
		/**
		 * Indicates that manga may contain sensitive information (18+, NSFW)
		 */
		isNsfw: Boolean,
		/**
		 * Absolute link to the cover
		 * @see largeCoverUrl
		 */
		coverUrl: String?,
		/**
		 * Tags (genres) of the manga
		 */
		tags: Set<MangaTag>,
		/**
		 * Manga status (ongoing, finished) or null if unknown
		 */
		state: MangaState?,
		/**
		 * Authors of the manga
		 */
		author: String?,
		/**
		 * Large cover url (absolute), null if is no large cover
		 * @see coverUrl
		 */
		largeCoverUrl: String? = null,
		/**
		 * Manga description, may be html or null
		 */
		description: String? = null,
		/**
		 * List of chapters
		 */
		chapters: List<MangaChapter>? = null,
		/**
		 * Manga source
		 */
		source: MangaSource,
	) : this(
		id = id,
		title = title,
		altTitles = setOfNotNull(altTitle?.nullIfEmpty()),
		url = url,
		publicUrl = publicUrl,
		rating = rating,
		contentRating = if (isNsfw) ContentRating.ADULT else null,
		coverUrl = coverUrl?.nullIfEmpty(),
		tags = tags,
		state = state,
		authors = setOfNotNull(author),
		largeCoverUrl = largeCoverUrl?.nullIfEmpty(),
		description = description?.nullIfEmpty(),
		chapters = chapters,
		source = source,
	)

	/**
	 * Author of the manga, may be null
	 */
	@Deprecated("Please use authors")
	public val author: String?
		get() = authors.firstOrNull()

	/**
	 * Alternative title (for example on other language), may be null
	 */
	@Deprecated("Please use altTitles")
	public val altTitle: String?
		get() = altTitles.firstOrNull()

	/**
	 * Return if manga has a specified rating
	 * @see rating
	 */
	public val hasRating: Boolean
		get() = rating > 0f && rating <= 1f

	@Deprecated("Use contentRating instead", ReplaceWith("contentRating == ContentRating.ADULT"))
	public val isNsfw: Boolean
		get() = contentRating == ContentRating.ADULT

	public fun getChapters(branch: String?): List<MangaChapter> {
		return chapters?.filter { x -> x.branch == branch }.orEmpty()
	}

	public fun findChapterById(id: Long): MangaChapter? = chapters?.findById(id)

	public fun requireChapterById(id: Long): MangaChapter = findChapterById(id)
		?: throw NoSuchElementException("Chapter with id $id not found")

	public fun getBranches(): Map<String?, Int> {
		if (chapters.isNullOrEmpty()) {
			return emptyMap()
		}
		val result = ArrayMap<String?, Int>()
		chapters.forEach {
			val key = it.branch
			result[key] = result.getOrDefault(key, 0) + 1
		}
		return result
	}
}
