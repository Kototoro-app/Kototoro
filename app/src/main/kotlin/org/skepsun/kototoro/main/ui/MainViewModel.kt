package org.skepsun.kototoro.main.ui

import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.plus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.skepsun.kototoro.core.exceptions.EmptyHistoryException
import org.skepsun.kototoro.core.github.AppUpdateRepository
import org.skepsun.kototoro.core.model.LocalVideoSource
import org.skepsun.kototoro.core.model.getContentType
import org.skepsun.kototoro.core.model.looksLikeLocalVideoContent
import org.skepsun.kototoro.core.model.looksLikeVideoUrl
import org.skepsun.kototoro.core.parser.ContentDataRepository
import org.skepsun.kototoro.core.prefs.AppSettings
import org.skepsun.kototoro.core.prefs.observeAsFlow
import org.skepsun.kototoro.core.prefs.observeAsStateFlow
import org.skepsun.kototoro.core.ui.BaseViewModel
import org.skepsun.kototoro.core.util.ext.MutableEventFlow
import org.skepsun.kototoro.core.util.ext.call
import org.skepsun.kototoro.explore.data.ContentSourcesRepository
import org.skepsun.kototoro.history.data.HistoryRepository
import org.skepsun.kototoro.main.domain.ReadingResumeEnabledUseCase
import org.skepsun.kototoro.parsers.model.Content
import org.skepsun.kototoro.tracker.domain.TrackingRepository
import org.skepsun.kototoro.work.domain.WorkResolver
import javax.inject.Inject

@HiltViewModel
class MainViewModel @Inject constructor(
	private val historyRepository: HistoryRepository,
	private val appUpdateRepository: AppUpdateRepository,
	trackingRepository: TrackingRepository,
	private val settings: AppSettings,
	readingResumeEnabledUseCase: ReadingResumeEnabledUseCase,
	private val sourcesRepository: ContentSourcesRepository,
	private val contentDataRepository: ContentDataRepository,
	private val workResolver: WorkResolver,
) : BaseViewModel() {

	val onOpenReader = MutableEventFlow<Content>()
	val onFirstStart = MutableEventFlow<Unit>()

	private val _topBarHeightPx = MutableStateFlow(0)
	val topBarHeightPx = _topBarHeightPx.asStateFlow()

	private val _bottomNavHeightPx = MutableStateFlow(0)
	val bottomNavHeightPx = _bottomNavHeightPx.asStateFlow()

	private val _topContentInsetPx = MutableStateFlow(0)
	val topContentInsetPx = _topContentInsetPx.asStateFlow()

	private val _bottomContentInsetPx = MutableStateFlow(0)
	val bottomContentInsetPx = _bottomContentInsetPx.asStateFlow()

	fun setTopBarHeightPx(height: Int) {
		_topBarHeightPx.value = height
	}

	fun setBottomNavHeightPx(height: Int) {
		_bottomNavHeightPx.value = height
	}

	fun setContentInsetsPx(top: Int, bottom: Int) {
		_topContentInsetPx.value = top
		_bottomContentInsetPx.value = bottom
	}

	val isResumeEnabled = readingResumeEnabledUseCase()
		.stateIn(viewModelScope + Dispatchers.Default, SharingStarted.WhileSubscribed(5000), false)

	val lastReadContent = historyRepository.observeLast()
		.withErrorHandling()
		.stateIn(
			scope = viewModelScope + Dispatchers.Default,
			started = SharingStarted.WhileSubscribed(5000),
			initialValue = null,
		)

	val appUpdate = appUpdateRepository.observeAvailableUpdate()

	val feedCounter = trackingRepository.observeUnreadUpdatesCount()
		.withErrorHandling()
		.stateIn(viewModelScope + Dispatchers.Default, SharingStarted.Lazily, 0)

	val isBottomNavPinned = flowOf(true).flowOn(Dispatchers.Default)

	val isNavFloating = settings.observeAsFlow(
		AppSettings.KEY_NAV_FLOATING,
	) {
		isNavFloating
	}.flowOn(Dispatchers.Default)

	val navHeight = settings.observe(
		AppSettings.KEY_NAV_HEIGHT,
		AppSettings.KEY_NAV_FLOATING_HEIGHT,
		AppSettings.KEY_NAV_FLOATING,
	).map {
		val floating = settings.isNavFloating
		if (floating) {
			settings.navFloatingHeight
		} else {
			settings.navHeight
		}
	}.flowOn(Dispatchers.Default)

	val isIncognitoModeEnabled = settings.observeAsStateFlow(
		scope = viewModelScope + Dispatchers.Default,
		key = AppSettings.KEY_INCOGNITO_MODE,
		valueProducer = { isIncognitoModeEnabled },
	)

	init {
		launchJob {
			appUpdateRepository.fetchUpdate()
		}
		launchJob(Dispatchers.Default) {
			if (sourcesRepository.isSetupRequired()) {
				onFirstStart.call(Unit)
			}
		}
	}

	fun openLastReader() {
		launchLoadingJob(Dispatchers.Default) {
			val rawContent = historyRepository.getLastOrNull() ?: throw EmptyHistoryException()
			val entityId = workResolver.resolveByMangaId(rawContent.id).entityId
			val preferredLocalMangaId = entityId?.let { workResolver.selectPreferredProjection(it) }
			val resolvedBase = preferredLocalMangaId
				?.takeIf { it != rawContent.id }
				?.let { contentDataRepository.findDisplayContentById(it, withChapters = false) }
				?: rawContent
			val content = if (
				resolvedBase.looksLikeLocalVideoContent() &&
				resolvedBase.source.getContentType() != org.skepsun.kototoro.parsers.model.ContentType.VIDEO
			) {
				resolvedBase.copy(
					source = LocalVideoSource,
					chapters = resolvedBase.chapters?.map { chapter ->
						if (
							chapter.source.getContentType() ==
							org.skepsun.kototoro.parsers.model.ContentType.MANGA &&
							chapter.url.looksLikeVideoUrl()
						) {
							chapter.copy(source = LocalVideoSource)
						} else {
							chapter
						}
					},
				)
			} else {
				resolvedBase
			}
			onOpenReader.call(content)
		}
	}

	fun setIncognitoMode(isEnabled: Boolean) {
		settings.isIncognitoModeEnabled = isEnabled
	}
}
