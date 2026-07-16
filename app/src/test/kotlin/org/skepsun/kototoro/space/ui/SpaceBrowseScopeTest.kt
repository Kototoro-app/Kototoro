package org.skepsun.kototoro.space.ui

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.explore.ui.model.BrowseGroupTab
import org.skepsun.kototoro.space.domain.BuiltInSpaces

class SpaceBrowseScopeTest {

	@Test
	fun `browse view models are isolated by space`() {
		browseViewModelKey(BuiltInSpaces.Manga) shouldBe "explore-space:builtin:manga"
		browseViewModelKey(BuiltInSpaces.Novel) shouldBe "explore-space:builtin:novel"
		browseViewModelKey(BuiltInSpaces.Anime) shouldBe "explore-space:builtin:anime"
		browseViewModelKey(null) shouldBe "explore-space:global"
	}

	@Test
	fun `built in spaces map to their browse content group`() {
		BuiltInSpaces.Manga.toBrowseGroupTab() shouldBe BrowseGroupTab.Content
		BuiltInSpaces.Novel.toBrowseGroupTab() shouldBe BrowseGroupTab.Novel
		BuiltInSpaces.Anime.toBrowseGroupTab() shouldBe BrowseGroupTab.Video
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
}
