package org.skepsun.kototoro.list.domain

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.db.entity.toEntity
import org.skepsun.kototoro.core.model.FavouriteCategory
import org.skepsun.kototoro.core.model.LocalMangaSource
import org.skepsun.kototoro.core.model.unwrap
import org.skepsun.kototoro.core.parser.external.ExternalContentSource
import org.skepsun.kototoro.core.parser.favicon.faviconUri
import org.skepsun.kototoro.parsers.model.ContentSource
import org.skepsun.kototoro.parsers.model.ContentTag

sealed interface ListFilterOption {

	@get:StringRes
	val titleResId: Int

	@get:DrawableRes
	val iconResId: Int

	val titleText: CharSequence?

	val groupKey: String

	fun getIconData(): Any? = null

	data object Downloaded : ListFilterOption {

		override val titleResId: Int
			get() = R.string.on_device

		override val iconResId: Int
			get() = R.drawable.ic_storage

		override val titleText: CharSequence?
			get() = null

		override val groupKey: String
			get() = "_downloaded"
	}

	enum class Macro(
		@StringRes override val titleResId: Int,
		@DrawableRes override val iconResId: Int,
	) : ListFilterOption {

		COMPLETED(R.string.status_completed, R.drawable.ic_state_finished),
		NEW_CHAPTERS(R.string.new_chapters, R.drawable.ic_updated),
		MULTI_PROJECTION(R.string.filter_multi_projection, R.drawable.ic_list_group),
		FAVORITE(R.string.favourites, R.drawable.ic_heart_outline),
		NSFW(R.string.nsfw, R.drawable.ic_nsfw),
		;

		override val titleText: CharSequence?
			get() = null

		override val groupKey: String
			get() = name
	}

	data class Branch(
		override val titleText: String?,
		val chaptersCount: Int,
	) : ListFilterOption {

		override val titleResId: Int
			get() = if (titleText == null) R.string.system_default else 0

		override val iconResId: Int
			get() = R.drawable.ic_language

		override val groupKey: String
			get() = "_branch"
	}

	data class Tag(
		val tag: ContentTag
	) : ListFilterOption {

		val tagId: Long = tag.toEntity().id

		override val titleResId: Int
			get() = 0

		override val iconResId: Int
			get() = R.drawable.ic_tag

		override val titleText: String
			get() = tag.title

		override val groupKey: String
			get() = "_tag"
	}

	data class Favorite(
		val category: FavouriteCategory
	) : ListFilterOption {

		override val titleResId: Int
			get() = 0

		override val iconResId: Int
			get() = R.drawable.ic_heart_outline

		override val titleText: String
			get() = category.title

		override val groupKey: String
			get() = "_favcat"
	}

	data class Source(
		val mangaSource: ContentSource
	) : ListFilterOption {
		override val titleResId: Int
			get() = when (mangaSource.unwrap()) {
				is ExternalContentSource -> R.string.external_source
				LocalMangaSource -> R.string.local_storage
				else -> 0
			}

		override val iconResId: Int
			get() = R.drawable.ic_web

		override val titleText: CharSequence?
			get() {
				val unwrapped = mangaSource.unwrap()
				return when (unwrapped) {
					is org.skepsun.kototoro.core.parser.kotatsu.KotatsuParserSource -> unwrapped.title
					is org.skepsun.kototoro.mihon.model.MihonMangaSource -> unwrapped.displayName
					is org.skepsun.kototoro.aniyomi.model.AniyomiAnimeSource -> unwrapped.displayName
					is org.skepsun.kototoro.ireader.model.IReaderMangaSource -> unwrapped.displayName
					is org.skepsun.kototoro.cloudstream.model.CloudstreamSource -> unwrapped.displayName
					is org.skepsun.kototoro.core.jsonsource.JsonContentSource -> unwrapped.displayName.ifBlank { unwrapped.name }
					is org.skepsun.kototoro.core.jsonsource.JsonSourceListSource -> unwrapped.displayName.ifBlank { unwrapped.name }
					else -> {
						if (unwrapped.name.startsWith("LOCAL") || unwrapped.name == "TEST") {
							mangaSource.name
						} else {
							val underlying = if (unwrapped is org.skepsun.kototoro.core.extensions.PluginContentSource) unwrapped.originalSource else unwrapped
							val titleMethod = try { underlying.javaClass.getMethod("getTitle") } catch (_: Exception) { null }
							if (titleMethod != null) {
								(titleMethod.invoke(underlying) as? String)?.takeIf { it.isNotBlank() } ?: mangaSource.name
							} else {
								mangaSource.name.replaceFirstChar { if (it.isLowerCase()) it.titlecase(java.util.Locale.getDefault()) else it.toString() }
							}
						}
					}
				}
			}

		override val groupKey: String
			get() = "_source"

		override fun getIconData() = mangaSource.faviconUri()
	}

	data class Inverted(
		val option: ListFilterOption,
		override val iconResId: Int,
		override val titleResId: Int,
		override val titleText: CharSequence?,
	) : ListFilterOption {

		override val groupKey: String
			get() = "_inv" + option.groupKey
	}

	companion object {

		val SFW
			get() = Inverted(
				option = Macro.NSFW,
				iconResId = R.drawable.ic_sfw,
				titleResId = R.string.sfw,
				titleText = null,
			)

		val NOT_FAVORITE
			get() = Inverted(
				option = Macro.FAVORITE,
				iconResId = R.drawable.ic_heart_off,
				titleResId = R.string.not_in_favorites,
				titleText = null,
			)
	}
}
