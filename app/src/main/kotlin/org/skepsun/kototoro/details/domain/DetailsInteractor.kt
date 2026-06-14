package org.skepsun.kototoro.details.domain

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import org.skepsun.kototoro.core.model.FavouriteCategory
import org.skepsun.kototoro.core.model.isNsfw
import org.skepsun.kototoro.core.prefs.AppSettings
import org.skepsun.kototoro.core.prefs.TriStateOption
import org.skepsun.kototoro.core.prefs.observeAsFlow
import org.skepsun.kototoro.details.data.ContentDetails
import org.skepsun.kototoro.favourites.domain.FavouritesRepository
import org.skepsun.kototoro.history.data.HistoryRepository
import org.skepsun.kototoro.local.data.LocalMangaRepository
import org.skepsun.kototoro.local.domain.model.LocalContent
import org.skepsun.kototoro.parsers.model.Content
import org.skepsun.kototoro.parsers.util.runCatchingCancellable
import org.skepsun.kototoro.scrobbling.common.domain.Scrobbler
import org.skepsun.kototoro.scrobbling.common.domain.model.ScrobblingInfo
import org.skepsun.kototoro.tracker.domain.TrackingRepository
import javax.inject.Inject

/* TODO: remove */
class DetailsInteractor @Inject constructor(
	private val historyRepository: HistoryRepository,
	private val favouritesRepository: FavouritesRepository,
	private val localContentRepository: LocalMangaRepository,
	private val trackingRepository: TrackingRepository,
	private val settings: AppSettings,
	private val scrobblers: Set<@JvmSuppressWildcards Scrobbler>,
) {

	fun observeFavourite(mangaId: Long): Flow<Set<FavouriteCategory>> {
		return favouritesRepository.observeCategoriesByWork(mangaId)
	}

	fun observeNewChapters(mangaId: Long): Flow<Int> {
		return settings.observeAsFlow(AppSettings.KEY_TRACKER_ENABLED) { isTrackerEnabled }
			.flatMapLatest { isEnabled ->
				if (isEnabled) {
					trackingRepository.observeNewChaptersCount(mangaId)
				} else {
					flowOf(0)
				}
			}
	}

	fun observeScrobblingInfo(mangaId: Long): Flow<List<ScrobblingInfo>> {
		val flows = scrobblers.map { scrobbler ->
			val observedFlow = runCatching {
				scrobbler.observeScrobblingInfo(mangaId)
			}.getOrNull() ?: flowOf(null)
			observedFlow.catch { emit(null) }
		}
		if (flows.isEmpty()) {
			return flowOf(emptyList())
		}
		return combine(flows) { scrobblingInfo ->
			scrobblingInfo.filterNotNull()
		}
	}

	fun observeIncognitoMode(mangaFlow: Flow<Content?>): Flow<TriStateOption> {
		return mangaFlow
			.filterNotNull()
			.distinctUntilChangedBy { it.isNsfw() }
			.combine(observeIncognitoMode()) { manga, globalIncognito ->
				when {
					globalIncognito -> TriStateOption.ENABLED
					manga.isNsfw() -> settings.incognitoModeForNsfw
					else -> TriStateOption.DISABLED
				}
			}
	}

	suspend fun updateLocal(subject: ContentDetails?, localContent: LocalContent): ContentDetails? {
		subject ?: return null
		return if (subject.id == localContent.manga.id) {
			if (subject.isLocal) {
				subject.copy(
					manga = localContent.manga,
				)
			} else {
				subject.copy(
					localContent = runCatchingCancellable {
						localContent.copy(
							manga = localContentRepository.getDetails(localContent.manga),
						)
					}.getOrNull() ?: subject.local,
				)
			}
		} else {
			subject
		}
	}

	suspend fun findRemote(seed: Content) = localContentRepository.getRemoteContent(seed)

	private fun observeIncognitoMode() = settings.observeAsFlow(AppSettings.KEY_INCOGNITO_MODE) {
		isIncognitoModeEnabled
	}
}
