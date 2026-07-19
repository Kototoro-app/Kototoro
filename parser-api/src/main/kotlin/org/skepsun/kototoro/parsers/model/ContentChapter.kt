package org.skepsun.kototoro.parsers.model

import org.skepsun.kototoro.parsers.util.formatSimple
import org.skepsun.kototoro.parsers.util.ifNullOrEmpty

public data class ContentChapter(
	/**
	 * An unique id of chapter
	 */
	@JvmField public val id: Long,
	/**
	 * User-readable name of chapter if provided by parser or null instead
	 * Do not pass manga title or chapter number here
	 */
	@JvmField public val title: String?,
	/**
	 * Chapter number starting from 1, 0 if unknown
	 */
	@JvmField public val number: Float,
	/**
	 * Volume number starting from 1, 0 if unknown
	 */
	@JvmField public val volume: Int,
	/**
	 * Relative url to chapter (**without** a domain) or any other uri.
	 * Used principally in parsers
	 */
	@JvmField public val url: String,
	/**
	 * User-readable name of scanlator (releaser) or null if unknown
	 */
	@JvmField public val scanlator: String?,
	/**
	 * Chapter upload date in milliseconds
	 */
	@JvmField public val uploadDate: Long,
	/**
	 * User-readable name of branch.
	 * A branch is a group of chapters that overlap (e.g. different languages)
	 */
	@JvmField public val branch: String?,
	@JvmField public val source: ContentSource,
	/**
	 * Opaque source-owned metadata preserved across storage and adapter round trips.
	 */
	@JvmField public val sourceData: String? = null,
) {

	@Deprecated("Use title instead of name", ReplaceWith("ContentChapter(id, title, number, volume, url, scanlator, uploadDate, branch, source)"))
	public constructor(
		id: Long,
		name: String?,
		number: Float,
		volume: Int,
		url: String,
		scanlator: String?,
		uploadDate: Long,
		branch: String?,
		source: ContentSource,
		@Suppress("UNUSED_PARAMETER") dummy: Boolean,
	) : this(
		id = id,
		title = name,
		number = number,
		volume = volume,
		url = url,
		scanlator = scanlator,
		uploadDate = uploadDate,
		branch = branch,
		source = source,
	)

	@Deprecated("Use title instead", ReplaceWith("title"))
	val name: String
		get() = title.ifNullOrEmpty {
			buildString {
				if (volume > 0) append("Vol ").append(volume).append(' ')
				if (number > 0) append("Chapter ").append(number.formatSimple()) else append("Unnamed")
			}
		}

	public fun numberString(): String? = if (number > 0f) {
		number.formatSimple()
	} else {
		null
	}

	public fun volumeString(): String? = if (volume > 0) {
		volume.toString()
	} else {
		null
	}
}
