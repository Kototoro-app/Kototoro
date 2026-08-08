package org.skepsun.kototoro.core.jsonsource

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.core.db.entity.JsonSourceType

class TVBoxRepositorySelectionTest {

	@Test
	fun `repository locator groups sites and preferred repository wins`() {
		val aowu = option("嗷呜", "https://example.com/aowu.json")
		val nanfeng = option("南风", "https://example.com/nanfeng.json")

		val selection = TVBoxRepositorySelector.resolve(listOf(aowu, nanfeng, aowu), nanfeng.id)

		assertEquals(setOf("嗷呜", "南风"), selection.options.mapTo(mutableSetOf()) { it.title })
		assertEquals(nanfeng.id, selection.activeId)
	}

	@Test
	fun `invalid preference falls back to first repository`() {
		val selection = TVBoxRepositorySelector.resolve(
			listOf(option("肥猫", "https://example.com/feimao.json")),
			"removed",
		)

		assertEquals("肥猫", selection.active?.title)
	}

	@Test
	fun `active repository filters only tvbox sources`() {
		val aowu = config("嗷呜", "https://example.com/aowu.json")
		val nanfeng = config("南风", "https://example.com/nanfeng.json")
		val activeId = TVBoxRepositorySelector.option(JsonSourceType.TVBOX, nanfeng)?.id

		assertFalse(TVBoxRepositorySelector.isVisible(JsonSourceType.TVBOX, aowu, activeId))
		assertTrue(TVBoxRepositorySelector.isVisible(JsonSourceType.TVBOX, nanfeng, activeId))
		assertTrue(TVBoxRepositorySelector.isVisible(JsonSourceType.LEGADO, "{}", activeId))
	}

	private fun option(title: String, locator: String): TVBoxRepositoryOption =
		requireNotNull(TVBoxRepositorySelector.option(JsonSourceType.TVBOX, config(title, locator)))

	private fun config(title: String, locator: String): String =
		"""{"site":{"name":"测试"},"meta":{"sourceTitle":"$title","sourceLocator":"$locator"}}"""
}
