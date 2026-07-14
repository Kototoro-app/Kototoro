package org.skepsun.kototoro.space.ui

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import org.skepsun.kototoro.core.util.ext.processLifecycleScope
import org.skepsun.kototoro.explore.ui.model.BrowseGroupTab
import org.skepsun.kototoro.parsers.model.ContentType
import org.skepsun.kototoro.space.domain.BuiltInSpaces
import org.skepsun.kototoro.space.domain.SpaceFeatureFlagsRepository
import org.skepsun.kototoro.space.domain.SpaceId
import org.skepsun.kototoro.space.domain.SpaceRepository
import org.skepsun.kototoro.space.domain.observeActiveSpaceScope
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SpaceBrowseScope @Inject constructor(
	spaceRepository: SpaceRepository,
	featureFlagsRepository: SpaceFeatureFlagsRepository,
) {
	val groupTab: StateFlow<BrowseGroupTab?> = spaceRepository
		.observeActiveSpaceScope(featureFlagsRepository)
		.map { it?.toBrowseGroupTab() }
		.stateIn(
			scope = processLifecycleScope,
			started = SharingStarted.Eagerly,
			initialValue = spaceRepository.activeSpace.value
				.takeIf { featureFlagsRepository.flags.value.effectiveSwitcherEnabled }
				?.toBrowseGroupTab(),
		)
}

fun StateFlow<BrowseGroupTab>.scopedToSpace(
	spaceBrowseScope: SpaceBrowseScope,
	coroutineScope: CoroutineScope,
): StateFlow<BrowseGroupTab> = scopedToSpace(
	spaceGroupTab = spaceBrowseScope.groupTab,
	coroutineScope = coroutineScope,
)

internal fun StateFlow<BrowseGroupTab>.scopedToSpace(
	spaceGroupTab: StateFlow<BrowseGroupTab?>,
	coroutineScope: CoroutineScope,
): StateFlow<BrowseGroupTab> = combine(this, spaceGroupTab) { fallback, spaceTab ->
	spaceTab ?: fallback
}.stateIn(
	scope = coroutineScope,
	started = SharingStarted.Eagerly,
	initialValue = spaceGroupTab.value ?: value,
)

internal fun SpaceId.toBrowseGroupTab(): BrowseGroupTab = when (this) {
	BuiltInSpaces.Novel -> BrowseGroupTab.Novel
	BuiltInSpaces.Anime -> BrowseGroupTab.Video
	else -> BrowseGroupTab.Content
}

internal fun BrowseGroupTab.toPrimaryContentType(): ContentType? = when (this) {
	BrowseGroupTab.Content -> ContentType.MANGA
	BrowseGroupTab.Novel -> ContentType.NOVEL
	BrowseGroupTab.Video -> ContentType.VIDEO
	BrowseGroupTab.All -> null
}
