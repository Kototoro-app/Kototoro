package org.skepsun.kototoro.space.ui

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.explore.ui.model.BrowseGroupTab
import org.skepsun.kototoro.space.domain.BuiltInSpaces
import org.skepsun.kototoro.space.domain.SpaceId
import org.skepsun.kototoro.space.domain.primarySpaceKind

class SpaceBrowseScopeTest {

	@Test
	fun `main view models are isolated by owner and space`() {
		spaceViewModelKey("home", BuiltInSpaces.Manga) shouldBe "home-space:builtin:manga"
		spaceViewModelKey("explore", BuiltInSpaces.Novel) shouldBe "explore-space:builtin:novel"
		spaceViewModelKey("history", BuiltInSpaces.Anime) shouldBe "history-space:builtin:anime"
		spaceViewModelKey("favorites", null) shouldBe "favorites-space:global"
	}

	@Test
	fun `built in spaces map to their browse content group`() {
		BuiltInSpaces.Manga.toBrowseGroupTab() shouldBe BrowseGroupTab.Content
		BuiltInSpaces.Novel.toBrowseGroupTab() shouldBe BrowseGroupTab.Novel
		BuiltInSpaces.Anime.toBrowseGroupTab() shouldBe BrowseGroupTab.Video
	}

	@Test
	fun `space binding exposes target identity synchronously`() {
		val mutableSpaceId = MutableStateFlow<org.skepsun.kototoro.space.domain.SpaceId?>(BuiltInSpaces.Manga)
		val binding = SpaceBrowseBinding(
			mutableSpaceId = mutableSpaceId,
			groupTab = MutableStateFlow(BrowseGroupTab.Content),
		)

		binding.bindSpace(BuiltInSpaces.Novel)

		binding.spaceId.value shouldBe BuiltInSpaces.Novel
	}

	@Test
	fun `disabled space scope falls back to global browse tab`() = runTest {
		val fallback = MutableStateFlow<BrowseGroupTab>(BrowseGroupTab.Video)
		val spaceGroupTab = MutableStateFlow<BrowseGroupTab?>(null)
		val scoped = fallback.scopedToSpace(spaceGroupTab, backgroundScope)

		runCurrent()
		scoped.value shouldBe BrowseGroupTab.Video

		fallback.value = BrowseGroupTab.Novel
		runCurrent()
		scoped.value shouldBe BrowseGroupTab.Novel
	}

	@Test
	fun `active space changes override the global browse tab`() = runTest {
		val fallback = MutableStateFlow<BrowseGroupTab>(BrowseGroupTab.All)
		val spaceGroupTab = MutableStateFlow<BrowseGroupTab?>(BrowseGroupTab.Content)
		val scoped = fallback.scopedToSpace(spaceGroupTab, backgroundScope)

		runCurrent()
		scoped.value shouldBe BrowseGroupTab.Content

		spaceGroupTab.value = BrowseGroupTab.Novel
		runCurrent()
		scoped.value shouldBe BrowseGroupTab.Novel

		spaceGroupTab.value = BrowseGroupTab.Video
		runCurrent()
		scoped.value shouldBe BrowseGroupTab.Video
	}

	@Test
	fun `disabled space scope does not subscribe to enabled sources`() = runTest {
		var sourcesObserved = false
		observeAllowedSourceNames(
			spaceIds = MutableStateFlow<SpaceId?>(null),
			spaces = MutableStateFlow(emptyList()),
			observeSources = {
				sourcesObserved = true
				MutableStateFlow(emptyList())
			},
			resolveSourceNames = { _, _ -> emptySet() },
		).first() shouldBe null
		sourcesObserved shouldBe false
	}

	@Test
	fun `custom space with all content types maps to All browse tab`() = runTest {
		val allTypes = org.skepsun.kototoro.parsers.model.ContentType.entries
			.filterNot { it == org.skepsun.kototoro.parsers.model.ContentType.OTHER }.toSet()
		val customSpaceId = SpaceId("custom:all")
		val customSpace = org.skepsun.kototoro.space.domain.SpaceContext(
			id = customSpaceId,
			kind = allTypes.primarySpaceKind(),
			allowedContentTypes = allTypes,
			title = "All Types Space",
			isBuiltIn = false,
		)
		observeGroupTab(
			spaceIds = MutableStateFlow<SpaceId?>(customSpaceId),
			spaces = MutableStateFlow(listOf(customSpace)),
		).first() shouldBe BrowseGroupTab.All
		allTypes.primarySpaceKind().toBrowseGroupTab() shouldBe BrowseGroupTab.All
	}

	@Test
	fun `custom space with all content types does not restrict sources when languages and source kinds are empty`() = runTest {
		// 本构建只放行漫画类内容，“全类型空间”指覆盖 [ContentScopePolicy.supportedTypes] 的空间。
		val allTypes = org.skepsun.kototoro.core.model.ContentScopePolicy.supportedTypes
		val customSpaceId = SpaceId("custom:all")
		val customSpace = org.skepsun.kototoro.space.domain.SpaceContext(
			id = customSpaceId,
			kind = org.skepsun.kototoro.space.domain.SpaceKind.ALL,
			allowedContentTypes = allTypes,
			title = "All Types Space",
			isBuiltIn = false,
		)
		observeAllowedSourceNames(
			spaceIds = MutableStateFlow<SpaceId?>(customSpaceId),
			spaces = MutableStateFlow(listOf(customSpace)),
			observeSources = { MutableStateFlow(emptyList()) },
			resolveSourceNames = { _, _ -> setOf("should_not_be_called") },
		).first() shouldBe null
	}
}
