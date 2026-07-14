package org.skepsun.kototoro.space.ui

import androidx.lifecycle.viewModelScope
import dagger.Reusable
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.plus
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
import org.skepsun.kototoro.parsers.model.Content
import org.skepsun.kototoro.parsers.model.ContentType
import org.skepsun.kototoro.space.domain.BuiltInSpaces
import org.skepsun.kototoro.space.domain.SpaceId
import org.skepsun.kototoro.space.domain.SpaceRepository
import javax.inject.Inject

data class SpaceResumeItem(
	val spaceId: SpaceId,
	val title: String,
	val content: Content,
	val canResume: Boolean,
)

data class SpaceResumeUiState(
	val items: Map<SpaceId, SpaceResumeItem> = emptyMap(),
)

@Reusable
class SpaceResumeStateSource @Inject constructor(
	historyRepository: HistoryRepository,
	private val networkState: NetworkState,
	private val settings: AppSettings,
) {
	private val recentBySpace = combine(
		BuiltInSpaces.contexts.map { context ->
			historyRepository.observeLast(context.id).map { context.id to it }
		},
	) { entries -> entries.toMap() }

	fun observe() = combine(
		recentBySpace,
		networkState,
		settings.observe(
			AppSettings.KEY_MAIN_FAB,
			AppSettings.KEY_INCOGNITO_MODE,
		).map { settings.isMainFabEnabled && !settings.isIncognitoModeEnabled },
	) { recent, isOnline, resumeEnabled ->
		buildSpaceResumeUiState(recent, isOnline, resumeEnabled)
	}.distinctUntilChanged()
}

@HiltViewModel
class SpaceResumeViewModel @Inject constructor(
	stateSource: SpaceResumeStateSource,
	private val spaceRepository: SpaceRepository,
) : BaseViewModel() {

	val onOpenReader = MutableEventFlow<Content>()

	val uiState = stateSource.observe().stateIn(
		scope = viewModelScope + Dispatchers.Default,
		started = SharingStarted.WhileSubscribed(5_000),
		initialValue = SpaceResumeUiState(),
	)

	fun resume(spaceId: SpaceId) {
		val item = uiState.value.items[spaceId]?.takeIf(SpaceResumeItem::canResume) ?: return
		launchLoadingJob(Dispatchers.Default) {
			spaceRepository.activate(spaceId)
			onOpenReader.call(item.content.normalizeLocalVideoSource())
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

internal fun buildSpaceResumeUiState(
	recent: Map<SpaceId, Content?>,
	isOnline: Boolean,
	resumeEnabled: Boolean,
): SpaceResumeUiState {
	return SpaceResumeUiState(
		items = recent.mapNotNull { (spaceId, content) ->
			content?.let {
				spaceId to SpaceResumeItem(
					spaceId = spaceId,
					title = it.title,
					content = it,
					canResume = resumeEnabled && (isOnline || it.isLocal),
				)
			}
		}.toMap(),
	)
}
