package org.skepsun.kototoro.scrobbling.common.ui.config

import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.plus
import kotlinx.coroutines.launch
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.db.MangaDatabase
import org.skepsun.kototoro.core.nav.AppRouter
import org.skepsun.kototoro.core.ui.BaseViewModel
import org.skepsun.kototoro.core.util.ext.MutableEventFlow
import org.skepsun.kototoro.core.util.ext.call
import org.skepsun.kototoro.core.util.ext.onFirst
import org.skepsun.kototoro.core.util.ext.require
import org.skepsun.kototoro.list.ui.model.EmptyState
import org.skepsun.kototoro.list.ui.model.ListModel
import org.skepsun.kototoro.scrobbling.common.domain.Scrobbler
import org.skepsun.kototoro.scrobbling.common.domain.model.ScrobblerService
import org.skepsun.kototoro.scrobbling.common.domain.model.ScrobblerUser
import org.skepsun.kototoro.scrobbling.common.domain.model.ScrobblingInfo
import org.skepsun.kototoro.scrobbling.common.domain.model.ScrobblingStatus
import org.skepsun.kototoro.core.parser.ContentDataRepository
import org.skepsun.kototoro.core.parser.ContentRepository
import org.skepsun.kototoro.core.model.isLocal
import org.skepsun.kototoro.history.data.HistoryRepository
import org.skepsun.kototoro.parsers.model.Content
import org.skepsun.kototoro.scrobbling.common.data.ScrobblingEntity
import android.content.Context
import org.skepsun.kototoro.core.LocalizedAppContext
import org.skepsun.kototoro.favourites.domain.FavouritesRepository
import org.skepsun.kototoro.core.model.getTitle
import org.skepsun.kototoro.core.model.getOriginLabel
import org.skepsun.kototoro.scrobbling.common.data.findScrobblingByWorkOrManga
import org.skepsun.kototoro.scrobbling.common.data.rebindScrobblingToManga
import org.skepsun.kototoro.tracking.discovery.domain.TrackingSiteMatcher
import org.skepsun.kototoro.work.domain.WorkResolver
import javax.inject.Inject

@HiltViewModel
class ScrobblerConfigViewModel @Inject constructor(
	savedStateHandle: SavedStateHandle,
	scrobblers: Set<@JvmSuppressWildcards Scrobbler>,
	private val db: MangaDatabase,
	private val mangaDataRepository: ContentDataRepository,
	private val historyRepository: HistoryRepository,
	private val mangaRepositoryFactory: ContentRepository.Factory,
	private val favouritesRepository: FavouritesRepository,
	private val trackingSiteMatcher: TrackingSiteMatcher,
	private val workResolver: WorkResolver,
	@LocalizedAppContext private val context: Context,
) : BaseViewModel() {

	private val scrobblerService = getScrobblerService(savedStateHandle)
	private val scrobbler = scrobblers.first { it.scrobblerService == scrobblerService }

	val titleResId = scrobbler.scrobblerService.titleResId

	val user = MutableStateFlow<ScrobblerUser?>(null)
	val onLoggedOut = MutableEventFlow<Unit>()

	private var contentFirstEmitted = false
	private val requestedPreviewKeys = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

	val content = scrobbler.observeAllScrobblingInfo()
		.onStart { loadingCounter.increment() }
		.onFirst { contentFirstEmitted = true; loadingCounter.decrement() }
		.onCompletion { cause ->
			if (cause != null && !contentFirstEmitted) loadingCounter.decrement()
		}
		.withErrorHandling()
		.map { buildContentList(it) }
		.stateIn(viewModelScope + Dispatchers.Default, SharingStarted.Eagerly, emptyList())

	init {
		scrobbler.user
			.onEach { user.value = it }
			.launchIn(viewModelScope + Dispatchers.Default)
	}

	fun onAuthCodeReceived(authCode: String) {
		launchLoadingJob(Dispatchers.Default) {
			val newUser = scrobbler.authorize(authCode)
			user.value = newUser
		}
	}

	fun logout() {
		launchLoadingJob(Dispatchers.Default) {
			scrobbler.logout()
			user.value = null
			onLoggedOut.call(Unit)
		}
	}

	val onSyncResult = MutableEventFlow<Int>()
	val onBindResult = MutableEventFlow<String>()

	fun syncLibrary() {
		launchLoadingJob(Dispatchers.Default) {
			val count = scrobbler.syncLibrary()
			onSyncResult.call(count)
		}
	}

	fun onContentBound(info: ScrobblingInfo) {
		if (info.coverUrl.isNotBlank()) return
		val key = "${info.scrobbler.id}:${info.targetId}:${info.mediaType.orEmpty()}"
		if (!requestedPreviewKeys.add(key)) return
		viewModelScope.launch(Dispatchers.Default) {
			runCatching {
				scrobbler.warmUpScrobblingInfo(info)
			}.onFailure {
				android.util.Log.w("ScrobblerConfigVM", "Failed to warm up preview for targetId=${info.targetId}", it)
			}
		}
	}

	fun bindContent(info: ScrobblingInfo, pickedContent: Content) {
		launchLoadingJob(Dispatchers.Default) {
			android.util.Log.d("ScrobblerConfigVM", "bindContent: info.mangaId=${info.mangaId}, info.targetId=${info.targetId}, info.chapter=${info.chapter}, pickedContent.id=${pickedContent.id}, pickedContent.title=${pickedContent.title}")
			// 1. Insert the online Content result into MangaDatabase via ContentDataRepository
			val storedContent = mangaDataRepository.storeContentAndReturn(pickedContent, replaceExisting = false)
			val mangaId = storedContent.id
			val boundContent = mangaDataRepository.findPreferredLocalContentById(mangaId, withChapters = true)
				?: mangaDataRepository.findContentById(mangaId, withChapters = true)
				?: storedContent
			android.util.Log.d("ScrobblerConfigVM", "bindContent: stored manga, mangaId=$mangaId")

			// 2. Re-link the tracker
			val currentEntity = db.findScrobblingByWorkOrManga(
				scrobbler = scrobbler.scrobblerService.id,
				mangaId = info.mangaId,
				workResolver = workResolver,
			)
			android.util.Log.d("ScrobblerConfigVM", "bindContent: currentEntity=$currentEntity")
			val reboundEntity = db.rebindScrobblingToManga(
				scrobbler = scrobbler.scrobblerService.id,
				sourceMangaId = info.mangaId,
				targetMangaId = mangaId,
				workResolver = workResolver,
			) {
				ScrobblingEntity(
					scrobbler = scrobbler.scrobblerService.id,
					id = info.targetId.toInt(),
					targetId = info.targetId,
					entityId = null,
					mangaId = mangaId,
					status = info.status?.name,
					chapter = info.chapter,
					comment = info.comment,
					rating = info.rating,
					mediaType = info.mediaType.orEmpty(),
				)
			}
			android.util.Log.d("ScrobblerConfigVM", "bindContent: rebound entity=$reboundEntity")
			runCatching {
				trackingSiteMatcher.confirmMatch(scrobbler.scrobblerService, mangaId, info.targetId)
			}

			// 3. Sync Reading Progress
			if (info.chapter > 0) {
				try {
					var mangaToSync = boundContent
					if (mangaToSync.chapters.isNullOrEmpty() && !mangaToSync.isLocal) {
						val repo = mangaRepositoryFactory.create(mangaToSync.source)
						val details = repo.getDetails(mangaToSync)
						mangaToSync = details.copy(chapters = details.chapters)
						mangaToSync = mangaDataRepository.updateProjectionSnapshot(mangaToSync)
					}
					
					val chapters = mangaToSync.chapters ?: emptyList()
					val targetChapterIndex = (info.chapter - 1).coerceIn(0, chapters.size - 1)
					android.util.Log.d("ScrobblerConfigVM", "bindContent: syncing progress, chapters.size=${chapters.size}, targetChapterIndex=$targetChapterIndex")
					if (chapters.isNotEmpty() && targetChapterIndex >= 0) {
						val targetChapter = chapters[targetChapterIndex]
						historyRepository.addOrUpdate(
							manga = mangaToSync,
							chapterId = targetChapter.id,
							page = 0,
							scroll = 0,
							percent = 1f, // Mark as completed
							force = true
						)
						android.util.Log.d("ScrobblerConfigVM", "bindContent: history synced for chapter=${targetChapter.id}")
					}
				} catch (e: Exception) {
					android.util.Log.e("ScrobblerConfigVM", "Failed to sync reading progress", e)
				}
			}
			

			
			android.util.Log.d("ScrobblerConfigVM", "bindContent: completed successfully")
			onBindResult.call(boundContent.title)
		}
	}

	suspend fun hasLocalContent(mangaId: Long): Boolean {
		if (mangaId == 0L) return false
		return db.getMangaDao().find(mangaId) != null
	}

	fun getScrobblerService(): ScrobblerService = scrobblerService



	private fun buildContentList(list: List<ScrobblingInfo>): List<ListModel> {
		if (list.isEmpty()) {
			return listOf(
				EmptyState(
					icon = R.drawable.ic_empty_history,
					textPrimary = R.string.nothing_here,
					textSecondary = R.string.scrobbling_empty_hint,
					actionStringRes = 0,
				),
			)
		}
		val grouped = list.groupBy { it.status }
		val statuses = ScrobblingStatus.entries
		val result = ArrayList<ListModel>(list.size + statuses.size)
		for (st in statuses) {
			val subList = grouped[st]
			if (subList.isNullOrEmpty()) {
				continue
			}
			result.add(st)
			result.addAll(subList)
		}
		return result
	}

	private fun getScrobblerService(
		savedStateHandle: SavedStateHandle,
	): ScrobblerService {
		val serviceId = savedStateHandle.get<Int>(AppRouter.KEY_ID) ?: 0
		if (serviceId != 0) {
			return ScrobblerService.entries.first { it.id == serviceId }
		}
		val uri = savedStateHandle.require<Uri>(AppRouter.KEY_DATA)
		return when (uri.host) {
			ScrobblerConfigActivity.HOST_SHIKIMORI_AUTH -> ScrobblerService.SHIKIMORI
			ScrobblerConfigActivity.HOST_ANILIST_AUTH -> ScrobblerService.ANILIST
			ScrobblerConfigActivity.HOST_MAL_AUTH -> ScrobblerService.MAL
			ScrobblerConfigActivity.HOST_KITSU_AUTH -> ScrobblerService.KITSU
			ScrobblerConfigActivity.HOST_BANGUMI_AUTH -> ScrobblerService.BANGUMI
			ScrobblerConfigActivity.HOST_MANGAUPDATES_AUTH -> ScrobblerService.MANGAUPDATES
			ScrobblerConfigActivity.HOST_SIMKL_AUTH -> ScrobblerService.SIMKL
			else -> error("Wrong scrobbler uri: $uri")
		}
	}
}
