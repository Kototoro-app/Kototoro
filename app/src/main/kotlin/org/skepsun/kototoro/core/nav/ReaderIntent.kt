package org.skepsun.kototoro.core.nav

import android.content.Context
import android.content.Intent
import org.skepsun.kototoro.BuildConfig
import org.skepsun.kototoro.bookmarks.domain.Bookmark
import org.skepsun.kototoro.core.model.parcelable.ParcelableContent
import org.skepsun.kototoro.parsers.model.Content
import org.skepsun.kototoro.reader.ui.ReaderActivity
import org.skepsun.kototoro.reader.ui.ReaderState

@JvmInline
value class ReaderIntent private constructor(
	val intent: Intent,
) {

	class Builder(context: Context) {

		private val intent = Intent(context, ReaderActivity::class.java)
			.setAction(ACTION_MANGA_READ)

		fun manga(manga: Content) = apply {
			intent.putExtra(AppRouter.KEY_MANGA, ParcelableContent(manga))
			intent.putExtra(EXTRA_TRANSLATED_LANGUAGE, manga.source.locale)
			intent.setData(AppRouter.shortContentUrl(manga.id))
		}

		fun languages(translatedLanguage: String?, sourceLanguage: String?) = apply {
			translatedLanguage?.takeIf { it.isNotBlank() }?.let {
				intent.putExtra(EXTRA_TRANSLATED_LANGUAGE, it)
			}
			sourceLanguage?.takeIf { it.isNotBlank() }?.let {
				intent.putExtra(EXTRA_SOURCE_LANGUAGE, it)
			}
		}

		fun mangaId(mangaId: Long) = apply {
			intent.putExtra(AppRouter.KEY_ID, mangaId)
			intent.setData(AppRouter.shortContentUrl(mangaId))
		}

		fun incognito() = apply {
			intent.putExtra(EXTRA_INCOGNITO, true)
		}

		fun branch(branch: String?) = apply {
			intent.putExtra(EXTRA_BRANCH, branch)
		}

		fun state(state: ReaderState?) = apply {
			intent.putExtra(EXTRA_STATE, state)
		}

		fun bookmark(bookmark: Bookmark) = manga(
			bookmark.manga,
		).state(
			ReaderState(
				chapterId = bookmark.chapterId,
				page = bookmark.page,
				scroll = bookmark.scroll,
			),
		)

		fun build() = ReaderIntent(intent)
	}

	companion object {
		const val ACTION_MANGA_READ = "${BuildConfig.APPLICATION_ID}.action.READ_MANGA"
		const val EXTRA_STATE = "state"
		const val EXTRA_BRANCH = "branch"
		const val EXTRA_INCOGNITO = "incognito"
		const val EXTRA_TRANSLATED_LANGUAGE = "translated_language"
		const val EXTRA_SOURCE_LANGUAGE = "source_language"
	}
}
