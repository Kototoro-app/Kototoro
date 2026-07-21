package org.skepsun.kototoro.space.ui

import androidx.lifecycle.viewModelScope
import dagger.Reusable
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.plus
import kotlinx.coroutines.withTimeoutOrNull
import org.skepsun.kototoro.core.model.LocalVideoSource
import org.skepsun.kototoro.core.model.getContentType
import org.skepsun.kototoro.core.model.isLocal
import org.skepsun.kototoro.core.model.looksLikeLocalVideoContent
import org.skepsun.kototoro.core.model.looksLikeVideoUrl
import org.skepsun.kototoro.core.os.NetworkState
import org.skepsun.kototoro.core.prefs.AppSettings
import org.skepsun.kototoro.core.prefs.observeAsFlow
import org.skepsun.kototoro.core.ui.BaseViewModel
import org.skepsun.kototoro.core.util.ext.MutableEventFlow
import org.skepsun.kototoro.core.util.ext.call
import org.skepsun.kototoro.history.data.HistoryRepository
import org.skepsun.kototoro.tracker.domain.TrackingRepository
import org.skepsun.kototoro.parsers.model.Content
import org.skepsun.kototoro.parsers.model.ContentType
import org.skepsun.kototoro.space.domain.SpaceId
import org.skepsun.kototoro.space.domain.SpaceCatalogRepository
import org.skepsun.kototoro.space.domain.SpaceContentPolicy
import org.skepsun.kototoro.space.domain.SpaceFeatureFlagsRepository
import org.skepsun.kototoro.space.domain.SpaceKind
import org.skepsun.kototoro.space.domain.SpaceRepository
import org.skepsun.kototoro.suggestions.domain.SuggestionRepository
import javax.inject.Inject

data class SpaceResumeItem(
	val spaceId: SpaceId,
	val title: String,
	val content: Content,
	val canResume: Boolean,
)

data class SpaceResumeUiState(
	val items: Map<SpaceId, SpaceResumeItem> = emptyMap(),
	val history: Map<SpaceId, List<SpaceResumeItem>> = emptyMap(),
	val updates: List<SpaceUpdateItem> = emptyList(),
	val recommendations: Map<SpaceId, List<Content>> = emptyMap(),
)

data class SpaceUpdateItem(
	val spaceId: SpaceId,
	val title: String,
	val content: Content,
	val newChapters: Int,
)

data class SpaceResumeRequest(
	val content: Content,
	val contentType: ContentType?,
)

@Reusable
class SpaceResumeStateSource @Inject constructor(
	historyRepository: HistoryRepository,
	trackingRepository: TrackingRepository,
	suggestionRepository: SuggestionRepository,
	catalogRepository: SpaceCatalogRepository,
	featureFlagsRepository: SpaceFeatureFlagsRepository,
	spaceContentPolicy: SpaceContentPolicy,
	private val networkState: NetworkState,
	private val settings: AppSettings,
) {
	private val activeContexts = combine(
		catalogRepository.spaces,
		featureFlagsRepository.flags,
	) { contexts, flags ->
		contexts.takeIf { flags.effectiveSwitcherEnabled }.orEmpty()
	}

	private val historyBySpace = combine(
		activeContexts,
		historyRepository.observeAll(HOME_HISTORY_LIMIT),
	) { contexts, history ->
		contexts.associate { context -> context.id to history.asSequence()
			.filter { content ->
				content.source.getContentType() in context.allowedContentTypes &&
					(spaceContentPolicy.allowedSourceNames(context.id)?.let { content.source.name in it } != false)
			}
			.take(WORKBENCH_HISTORY_LIMIT)
			.toList() }
	}
	private val recommendationsBySpace = combine(
		activeContexts,
		suggestionRepository.observeAll(HOME_RECOMMENDATIONS_LIMIT, emptySet()),
	) { contexts, recommendations ->
		contexts.associate { context -> context.id to recommendations.asSequence()
			.filter { content ->
				content.source.getContentType() in context.allowedContentTypes &&
					(spaceContentPolicy.allowedSourceNames(context.id)?.let { content.source.name in it } != false)
			}
			.take(WORKBENCH_RECOMMENDATIONS_LIMIT)
			.toList() }
	}
	private val updates = activeContexts.flatMapLatest { contexts ->
		trackingRepository.observeUpdatedContent(12, emptySet()).map { trackedItems ->
			trackedItems.flatMap { tracked ->
				val contentType = tracked.manga.source.getContentType()
				contexts.asSequence()
					.filter { context ->
						contentType in context.allowedContentTypes &&
							(spaceContentPolicy.allowedSourceNames(context.id)
								?.let { tracked.manga.source.name in it } != false)
					}
					.map { context ->
						SpaceUpdateItem(
							spaceId = context.id,
							title = tracked.manga.title,
							content = tracked.manga,
							newChapters = tracked.newChapters,
						)
					}
					.toList()
			}
		}
	}

	fun observe() = combine(
		historyBySpace,
		updates,
		recommendationsBySpace,
		networkState,
		settings.observe(
			AppSettings.KEY_MAIN_FAB,
			AppSettings.KEY_INCOGNITO_MODE,
		).map { settings.isMainFabEnabled && !settings.isIncognitoModeEnabled },
	) { history, updates, recommendations, isOnline, resumeEnabled ->
		buildSpaceResumeUiState(
			recent = history.mapValues { it.value.firstOrNull() },
			isOnline = isOnline,
			resumeEnabled = resumeEnabled,
			updates = updates,
			history = history,
			recommendations = recommendations,
		)
	}.distinctUntilChanged()
}

private const val WORKBENCH_HISTORY_LIMIT = 12
private const val HOME_HISTORY_LIMIT = 64
private const val HOME_RECOMMENDATIONS_LIMIT = 64
private const val WORKBENCH_RECOMMENDATIONS_LIMIT = 12

@HiltViewModel
class SpaceResumeViewModel @Inject constructor(
	stateSource: SpaceResumeStateSource,
	private val spaceRepository: SpaceRepository,
	private val catalogRepository: SpaceCatalogRepository,
) : BaseViewModel() {

	val onOpenReader = MutableEventFlow<SpaceResumeRequest>()

	val uiState = stateSource.observe().stateIn(
		scope = viewModelScope + Dispatchers.Default,
		started = SharingStarted.WhileSubscribed(5_000),
		initialValue = SpaceResumeUiState(),
	)

	fun resume(spaceId: SpaceId) {
		launchLoadingJob(Dispatchers.Default) {
			val item = uiState.value.items[spaceId]?.takeIf(SpaceResumeItem::canResume)
				?: withTimeoutOrNull(2_000L) {
					uiState.map { state ->
						state.items[spaceId]?.takeIf(SpaceResumeItem::canResume)
					}.first { it != null }
				}
				?: return@launchLoadingJob
			spaceRepository.activate(spaceId)
			onOpenReader.call(
				SpaceResumeRequest(
					content = item.content.normalizeLocalVideoSource(),
					contentType = catalogRepository.find(spaceId)?.kind?.toContentType(),
				),
			)
		}
	}

	private fun Content.normalizeLocalVideoSource(): Content {
		if (!looksLikeLocalVideoContent() || source.getContentType() == ContentType.VIDEO) return this
		return copy(
			source = LocalVideoSource,
			chapters = chapters?.map { chapter ->
				if (chapter.source.getContentType() == ContentType.MANGA && chapter.url.looksLikeVideoUrl()) {
					chapter.copy(source = LocalVideoSource)
				} else {
					chapter
				}
			},
		)
	}
}

private fun SpaceKind.toContentType(): ContentType = when (this) {
	SpaceKind.MANGA -> ContentType.MANGA
	SpaceKind.NOVEL -> ContentType.NOVEL
	SpaceKind.ANIME -> ContentType.VIDEO
}

internal fun buildSpaceResumeUiState(
	recent: Map<SpaceId, Content?>,
	isOnline: Boolean,
	resumeEnabled: Boolean,
	updates: List<SpaceUpdateItem> = emptyList(),
	history: Map<SpaceId, List<Content>> = emptyMap(),
	recommendations: Map<SpaceId, List<Content>> = emptyMap(),
): SpaceResumeUiState {
	fun Content.toResumeItem(spaceId: SpaceId) = SpaceResumeItem(
		spaceId = spaceId,
		title = title,
		content = this,
		canResume = resumeEnabled && (isOnline || isLocal),
	)
	return SpaceResumeUiState(
		items = recent.mapNotNull { (spaceId, content) ->
			content?.let { spaceId to it.toResumeItem(spaceId) }
		}.toMap(),
		history = history.mapValues { (spaceId, contents) ->
			contents.map { it.toResumeItem(spaceId) }
		},
		updates = updates,
		recommendations = recommendations,
	)
}
