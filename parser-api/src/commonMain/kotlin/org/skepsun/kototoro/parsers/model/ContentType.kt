package org.skepsun.kototoro.parsers.model

public enum class ContentType {

	/**
	 * Standard manga, manhua, webtoons, etc
	 */
	MANGA,

	MANHWA,

	MANHUA,

	/**
	 * Adult manga (explicit).
	 */
	HENTAI_MANGA,

	/**
	 * Adult novels (explicit).
	 */
	HENTAI_NOVEL,

	/**
	 * Adult videos (explicit).
	 */
	HENTAI_VIDEO,

	/**
	 * Western comics
	 */
	COMICS,

	/**
	 * Video content (e.g., anime clips or full videos)
	 */
	VIDEO,

	NOVEL,

	/**
	 * Use this type if no other suits your needs. For example, for an indie manga
	 */

	ONE_SHOT,
	DOUJINSHI,
	IMAGE_SET,
	ARTIST_CG,
	GAME_CG,
	OTHER,
}
