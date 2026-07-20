package org.skepsun.kototoro.parsers.model

import androidx.collection.ArrayMap
import org.skepsun.kototoro.parsers.util.findById
import org.skepsun.kototoro.parsers.util.nullIfEmpty

public data class Content(
	/**
	 * Unique identifier for manga
	 */
	@JvmField public val id: Long,
	/**
	 * Content title, human-readable
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
	@JvmField public val tags: Set<ContentTag>,
	/**
	 * Content status (ongoing, finished) or null if unknown
	 */
	@JvmField public val state: ContentState?,
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
	 * Content description, may be html or null
	 */
	@JvmField public val description: String? = null,
	/**
	 * List of chapters
	 */
	@JvmField public val chapters: List<ContentChapter>? = null,
	/**
	 * Content source
	 */
	@JvmField public val source: ContentSource,
	/**
	 * Opaque source-owned metadata preserved across storage and adapter round trips.
	 */
	@JvmField public val sourceData: String? = null,
) {
	@Deprecated(
		message = "Binary compatibility bridge for parsers compiled before sourceData",
		level = DeprecationLevel.HIDDEN,
	)
	public constructor(
		id: Long,
		title: String,
		altTitles: Set<String>,
		url: String,
		publicUrl: String,
		rating: Float,
		contentRating: ContentRating?,
		coverUrl: String?,
		tags: Set<ContentTag>,
		state: ContentState?,
		authors: Set<String>,
		largeCoverUrl: String?,
		description: String?,
		chapters: List<ContentChapter>?,
		source: ContentSource,
	) : this(
		id = id,
		title = title,
		altTitles = altTitles,
		url = url,
		publicUrl = publicUrl,
		rating = rating,
		contentRating = contentRating,
		coverUrl = coverUrl,
		tags = tags,
		state = state,
		authors = authors,
		largeCoverUrl = largeCoverUrl,
		description = description,
		chapters = chapters,
		source = source,
		sourceData = null,
	)

	@Suppress("UNUSED_PARAMETER")
	@Deprecated(
		message = "Binary compatibility bridge for parsers compiled before sourceData",
		level = DeprecationLevel.HIDDEN,
	)
	public constructor(
		id: Long,
		title: String,
		altTitles: Set<String>,
		url: String,
		publicUrl: String,
		rating: Float,
		contentRating: ContentRating?,
		coverUrl: String?,
		tags: Set<ContentTag>,
		state: ContentState?,
		authors: Set<String>,
		largeCoverUrl: String?,
		description: String?,
		chapters: List<ContentChapter>?,
		source: ContentSource,
		mask: Int,
		marker: kotlin.jvm.internal.DefaultConstructorMarker?,
	) : this(
		id = id,
		title = title,
		altTitles = altTitles,
		url = url,
		publicUrl = publicUrl,
		rating = rating,
		contentRating = contentRating,
		coverUrl = coverUrl,
		tags = tags,
		state = state,
		authors = authors,
		largeCoverUrl = largeCoverUrl.takeUnless { mask and (1 shl 11) != 0 },
		description = description.takeUnless { mask and (1 shl 12) != 0 },
		chapters = chapters.takeUnless { mask and (1 shl 13) != 0 },
		source = source,
		sourceData = null,
	)

	@Deprecated("Accepts rating as Int; use Float in range 0..1 instead")
	public constructor(
		id: Long,
		title: String,
		altTitles: Set<String>,
		url: String,
		publicUrl: String,
		rating: Int,
		contentRating: ContentRating?,
		coverUrl: String?,
		tags: Set<ContentTag>,
		state: ContentState?,
		authors: Set<String>,
		largeCoverUrl: String? = null,
		description: String? = null,
		chapters: List<ContentChapter>? = null,
		source: ContentSource,
	) : this(
		id = id,
		title = title,
		altTitles = altTitles,
		url = url,
		publicUrl = publicUrl,
		rating = rating.toFloat(),
		contentRating = contentRating,
		coverUrl = coverUrl?.nullIfEmpty(),
		tags = tags,
		state = state,
		authors = authors,
		largeCoverUrl = largeCoverUrl?.nullIfEmpty(),
		description = description?.nullIfEmpty(),
		chapters = chapters,
		source = source,
	)

	@Deprecated("Use other constructor")
	public constructor(
		/**
		 * Unique identifier for manga
		 */
		id: Long,
		/**
		 * Content title, human-readable
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
		tags: Set<ContentTag>,
		/**
		 * Content status (ongoing, finished) or null if unknown
		 */
		state: ContentState?,
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
		 * Content description, may be html or null
		 */
		description: String? = null,
		/**
		 * List of chapters
		 */
		chapters: List<ContentChapter>? = null,
		/**
		 * Content source
		 */
		source: ContentSource,
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

	public fun getChapters(branch: String?): List<ContentChapter> {
		return chapters?.filter { x -> x.branch == branch }.orEmpty()
	}

	public fun findChapterById(id: Long): ContentChapter? = chapters?.findById(id)

	public fun requireChapterById(id: Long): ContentChapter = findChapterById(id)
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

	public companion object {
		@Suppress("UNUSED_PARAMETER")
		@JvmStatic
		public fun `copy$default`(
			content: Content,
			id: Long,
			title: String?,
			altTitles: Set<String>?,
			url: String?,
			publicUrl: String?,
			rating: Float,
			contentRating: ContentRating?,
			coverUrl: String?,
			tags: Set<ContentTag>?,
			state: ContentState?,
			authors: Set<String>?,
			largeCoverUrl: String?,
			description: String?,
			chapters: List<ContentChapter>?,
			source: ContentSource?,
			mask: Int,
			marker: Any?,
		): Content {
			return content.copy(
				id = id.takeUnless { mask and (1 shl 0) != 0 } ?: content.id,
				title = if (mask and (1 shl 1) != 0) content.title else requireNotNull(title),
				altTitles = if (mask and (1 shl 2) != 0) content.altTitles else requireNotNull(altTitles),
				url = if (mask and (1 shl 3) != 0) content.url else requireNotNull(url),
				publicUrl = if (mask and (1 shl 4) != 0) content.publicUrl else requireNotNull(publicUrl),
				rating = rating.takeUnless { mask and (1 shl 5) != 0 } ?: content.rating,
				contentRating = if (mask and (1 shl 6) != 0) content.contentRating else contentRating,
				coverUrl = if (mask and (1 shl 7) != 0) content.coverUrl else coverUrl,
				tags = if (mask and (1 shl 8) != 0) content.tags else requireNotNull(tags),
				state = if (mask and (1 shl 9) != 0) content.state else state,
				authors = if (mask and (1 shl 10) != 0) content.authors else requireNotNull(authors),
				largeCoverUrl = if (mask and (1 shl 11) != 0) content.largeCoverUrl else largeCoverUrl,
				description = if (mask and (1 shl 12) != 0) content.description else description,
				chapters = if (mask and (1 shl 13) != 0) content.chapters else chapters,
				source = if (mask and (1 shl 14) != 0) content.source else requireNotNull(source),
				sourceData = content.sourceData,
			)
		}
	}
}
