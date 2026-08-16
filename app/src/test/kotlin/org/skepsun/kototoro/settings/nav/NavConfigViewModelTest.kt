package org.skepsun.kototoro.settings.nav

import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.core.prefs.AppSettings
import org.skepsun.kototoro.core.prefs.NavItem
import org.skepsun.kototoro.core.ui.util.ActivityRecreationHandle

@OptIn(ExperimentalCoroutinesApi::class)
class NavConfigViewModelTest {

	private val dispatcher = UnconfinedTestDispatcher()

	@BeforeEach
	fun setUp() = Dispatchers.setMain(dispatcher)

	@AfterEach
	fun tearDown() = Dispatchers.resetMain()

	@Test
	fun `configured navigation is available on the first frame`() {
		val configured = listOf(NavItem.HOME, NavItem.HISTORY)
		val settings = mockk<AppSettings>(relaxed = true) {
			every { mainNavItems } returns configured
		}

		val viewModel = NavConfigViewModel(settings, mockk<ActivityRecreationHandle>(relaxed = true))

		assertEquals(configured, viewModel.configuredItems.value.map { it.item })
		assertTrue(viewModel.availableItems.value.isNotEmpty())
		assertTrue(viewModel.canShowAddAction.value)
		assertTrue(viewModel.canAddAction.value)
	}
}
