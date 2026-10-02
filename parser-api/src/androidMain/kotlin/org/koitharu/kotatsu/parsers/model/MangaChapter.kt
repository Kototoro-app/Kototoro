package org.koitharu.kotatsu.parsers.model

import org.koitharu.kotatsu.parsers.util.formatSimple
import org.koitharu.kotatsu.parsers.util.ifNullOrEmpty

public data class MangaChapter(
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
	@JvmField public val source: MangaSource,
) {

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
