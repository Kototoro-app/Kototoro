package org.skepsun.kototoro.space.ui

import androidx.compose.runtime.staticCompositionLocalOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import org.skepsun.kototoro.core.util.ext.processLifecycleScope
import org.skepsun.kototoro.explore.data.ContentSourcesRepository
import org.skepsun.kototoro.explore.data.SourceRule
import org.skepsun.kototoro.explore.data.SourceRuleResolver
import org.skepsun.kototoro.explore.ui.model.BrowseGroupTab
import org.skepsun.kototoro.parsers.model.ContentType
import org.skepsun.kototoro.space.domain.BuiltInSpaces
import org.skepsun.kototoro.space.domain.SpaceCatalogRepository
import org.skepsun.kototoro.space.domain.SpaceFeatureFlagsRepository
import org.skepsun.kototoro.space.domain.SpaceId
import org.skepsun.kototoro.space.domain.SpaceRepository
import org.skepsun.kototoro.space.domain.observeActiveSpaceScope
import javax.inject.Inject
import javax.inject.Singleton

val LocalBrowseSpaceId = staticCompositionLocalOf<SpaceId?> { null }

internal fun browseViewModelKey(spaceId: SpaceId?): String =
	"explore-space:${spaceId?.value ?: "global"}"

@Singleton
class SpaceBrowseScope @Inject constructor(
	private val spaceRepository: SpaceRepository,
	private val featureFlagsRepository: SpaceFeatureFlagsRepository,
	private val catalogRepository: SpaceCatalogRepository,
	private val sourcesRepository: ContentSourcesRepository,
	private val sourceRuleResolver: SourceRuleResolver,
) {
	private val activeSpace = spaceRepository.observeActiveSpaceScope(featureFlagsRepository)
	val currentSpaceId: SpaceId?
		get() = spaceRepository.activeSpace.value
			.takeIf { featureFlagsRepository.flags.value.effectiveSwitcherEnabled }

	val groupTab: StateFlow<BrowseGroupTab?> = observeGroupTab(activeSpace)
		.stateIn(
			scope = processLifecycleScope,
			started = SharingStarted.Eagerly,
			initialValue = currentSpaceId?.let { id -> catalogRepository.find(id)?.kind?.toBrowseGroupTab() },
		)

	val allowedSourceNames: StateFlow<Set<String>?> = observeAllowedSourceNames(activeSpace)
		.stateIn(
			scope = processLifecycleScope,
			started = SharingStarted.Eagerly,
			initialValue = null,
		)

	fun observeGroupTab(spaceIds: Flow<SpaceId?>): Flow<BrowseGroupTab?> = combine(
		spaceIds,
		catalogRepository.spaces,
	) { spaceId, spaces ->
		spaceId?.let { id -> spaces.firstOrNull { it.id == id }?.kind?.toBrowseGroupTab() }
	}

	fun observeAllowedSourceNames(spaceIds: Flow<SpaceId?>): Flow<Set<String>?> = combine(
		spaceIds,
		catalogRepository.spaces,
		sourcesRepository.observeEnabledSources(),
	) { spaceId, spaces, sources ->
		val context = spaceId?.let { id -> spaces.firstOrNull { it.id == id } } ?: return@combine null
		if (context.sourceLanguages.isEmpty() && context.sourceKinds.isEmpty() && context.isBuiltIn) {
			null
		} else {
			sourceRuleResolver.resolveSourceNames(
				SourceRule(
					languages = context.sourceLanguages,
					contentTypes = context.allowedContentTypes,
					sourceTypes = context.sourceKinds,
				),
				sources,
			)
		}
	}
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

private fun org.skepsun.kototoro.space.domain.SpaceKind.toBrowseGroupTab(): BrowseGroupTab = when (this) {
	org.skepsun.kototoro.space.domain.SpaceKind.NOVEL -> BrowseGroupTab.Novel
	org.skepsun.kototoro.space.domain.SpaceKind.ANIME -> BrowseGroupTab.Video
	org.skepsun.kototoro.space.domain.SpaceKind.MANGA -> BrowseGroupTab.Content
}

internal fun BrowseGroupTab.toPrimaryContentType(): ContentType? = when (this) {
	BrowseGroupTab.Content -> ContentType.MANGA
	BrowseGroupTab.Novel -> ContentType.NOVEL
	BrowseGroupTab.Video -> ContentType.VIDEO
	BrowseGroupTab.All -> null
}
