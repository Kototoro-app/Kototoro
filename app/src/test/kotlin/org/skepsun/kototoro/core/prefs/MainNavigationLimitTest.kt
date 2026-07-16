package org.skepsun.kototoro.core.prefs

import io.kotest.matchers.collections.shouldContainExactly
import org.junit.jupiter.api.Test

class MainNavigationLimitTest {

	@Test
	fun `main navigation keeps at most five distinct destinations`() {
		listOf(
			NavItem.HOME,
			NavItem.FAVORITES,
			NavItem.EXPLORE,
			NavItem.HISTORY,
			NavItem.FEED,
			NavItem.HOME,
		).limitMainNavigationItems() shouldContainExactly listOf(
			NavItem.HOME,
			NavItem.FAVORITES,
			NavItem.EXPLORE,
			NavItem.HISTORY,
			NavItem.FEED,
		)
	}
}
