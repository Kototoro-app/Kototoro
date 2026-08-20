package org.skepsun.kototoro.explore.ui.model

import androidx.annotation.StringRes
import androidx.annotation.DrawableRes
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.jsonsource.ContentGroup
import org.skepsun.kototoro.core.jsonsource.OriginGroup
import org.skepsun.kototoro.parsers.model.ContentType

/**
 * Represents a tab in the browse page for filtering sources by group.
 */
sealed class BrowseGroupTab(
	@StringRes val titleRes: Int,
	@DrawableRes val iconRes: Int,
	val id: String,
) {
	/**
	 * Show all sources without filtering
	 */
	object All : BrowseGroupTab(R.string.all, R.drawable.ic_filter_content_type, "all")
	
	/**
	 * Show only manga sources
	 */
	object Content : BrowseGroupTab(R.string.manga, R.drawable.ic_content_manga, "manga")
	
	/**
	 * Show only novel sources
	 */
	object Novel : BrowseGroupTab(R.string.novel, R.drawable.ic_content_novel, "novel")
	
	/**
	 * Show only video sources
	 */
	object Video : BrowseGroupTab(R.string.video, R.drawable.ic_content_video, "video")
	
	companion object {
		/**
		 * Get all available tabs in order
		 */
		fun getAllTabs(): List<BrowseGroupTab> = listOf(
			All,
			Content,
			Novel,
			Video,
		)
		
		/**
		 * Find tab by ID
		 */
		fun fromId(id: String): BrowseGroupTab = when (id) {
			"all" -> All
			"manga" -> Content
			"novel" -> Novel
			"video" -> Video
			// Legacy IDs now fall back to All
			"json", "mihon", "aniyomi" -> All
			else -> All
		}

		/**
		 * Get available tabs based on NSFW setting
		 */
		fun getAvailableTabs(isNsfwEnabled: Boolean): List<BrowseGroupTab> {
			return getAllTabs()
		}
	}
	
	/**
	 * Check if this tab matches a content group
	 */
	fun matchesContentGroup(group: ContentGroup): Boolean = when (this) {
		All -> true
		Content -> group == ContentGroup.MANGA || group == ContentGroup.HENTAI_MANGA
		Novel -> group == ContentGroup.NOVEL || group == ContentGroup.HENTAI_NOVEL
		Video -> group == ContentGroup.VIDEO || group == ContentGroup.HENTAI_VIDEO
	}

	/**
	 * Check if a work matches this tab based on its persisted content type.
	 * Unlike [matchesContentGroup], which classifies by the *source*, this
	 * matches the type recorded on the entity / projection — so novels and
	 * videos whose sources the source-group heuristic labels as MANGA/OTHER
	 * (e.g. anonymous or legacy JSON sources restored from backups) still
	 * appear under the Novel/Video chips.
	 */
	fun matchesContentType(type: ContentType): Boolean = when (this) {
		All -> true
		Content -> type == ContentType.MANGA ||
			type == ContentType.MANHWA ||
			type == ContentType.MANHUA ||
			type == ContentType.COMICS ||
			type == ContentType.ONE_SHOT ||
			type == ContentType.DOUJINSHI ||
			type == ContentType.IMAGE_SET ||
			type == ContentType.ARTIST_CG ||
			type == ContentType.GAME_CG ||
			type == ContentType.HENTAI_MANGA
		Novel -> type == ContentType.NOVEL || type == ContentType.HENTAI_NOVEL
		Video -> type == ContentType.VIDEO || type == ContentType.HENTAI_VIDEO
	}
	
	/**
	 * Check if this tab matches an origin group
	 */
	fun matchesOriginGroup(group: OriginGroup): Boolean = when (this) {
		All -> true
		else -> true // Content-based tabs don't filter by origin
	}

	/**
	 * Check if this tab supports the given source tag.
	 */
	fun supportsSourceTag(tag: SourceTag): Boolean = when (this) {
		All -> true
		Content -> tag == SourceTag.BUILTIN || tag == SourceTag.MIHON || tag == SourceTag.LEGADO
		Novel -> tag == SourceTag.BUILTIN || tag == SourceTag.LEGADO || tag == SourceTag.IREADER || tag == SourceTag.LNREADER
		Video -> tag == SourceTag.BUILTIN || tag == SourceTag.ANIYOMI || tag == SourceTag.TVBOX || tag == SourceTag.CLOUDSTREAM
	}
}
