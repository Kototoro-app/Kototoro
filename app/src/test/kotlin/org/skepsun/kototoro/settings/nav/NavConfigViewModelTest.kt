package org.skepsun.kototoro.settings.nav

import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
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
import org.skepsun.kototoro.main.ui.MainActivity

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

	@Test
	fun `change is persisted immediately even when leaving before the debounce`() {
		val settings = mockk<AppSettings>(relaxed = true) {
			every { mainNavItems } returns listOf(NavItem.HOME)
		}
		val viewModel = NavConfigViewModel(settings, mockk<ActivityRecreationHandle>(relaxed = true))

		viewModel.addItem(NavItem.HISTORY)

		// Leaving the screen right away cancels viewModelScope mid-debounce; the write
		// must already be on disk (the recreation is the only debounced part).
		viewModel.viewModelScope.cancel()
		verify(exactly = 1) { settings.mainNavItems = listOf(NavItem.HOME, NavItem.HISTORY) }
	}

	@Test
	fun `drag reorder is persisted immediately`() {
		val settings = mockk<AppSettings>(relaxed = true) {
			every { mainNavItems } returns listOf(NavItem.HOME, NavItem.HISTORY, NavItem.FAVORITES)
		}
		val viewModel = NavConfigViewModel(settings, mockk<ActivityRecreationHandle>(relaxed = true))

		viewModel.reorder(fromPos = 2, toPos = 0)

		verify(exactly = 1) {
			settings.mainNavItems = listOf(NavItem.FAVORITES, NavItem.HOME, NavItem.HISTORY)
		}
	}

	@Test
	fun `consecutive drag moves use the latest item position`() {
		val settings = mockk<AppSettings>(relaxed = true) {
			every { mainNavItems } returns listOf(NavItem.HOME, NavItem.HISTORY, NavItem.FAVORITES)
		}
		val viewModel = NavConfigViewModel(settings, mockk<ActivityRecreationHandle>(relaxed = true))

		viewModel.moveItem(NavItem.FAVORITES, direction = -1)
		viewModel.moveItem(NavItem.FAVORITES, direction = -1)

		verify(exactly = 1) {
			settings.mainNavItems = listOf(NavItem.HOME, NavItem.FAVORITES, NavItem.HISTORY)
		}
		verify(exactly = 1) {
			settings.mainNavItems = listOf(NavItem.FAVORITES, NavItem.HOME, NavItem.HISTORY)
		}
	}

	@Test
	fun `drag beyond the list clamps the item to the edge`() {
		val settings = mockk<AppSettings>(relaxed = true) {
			every { mainNavItems } returns listOf(NavItem.HOME, NavItem.HISTORY, NavItem.FAVORITES)
		}
		val viewModel = NavConfigViewModel(settings, mockk<ActivityRecreationHandle>(relaxed = true))

		viewModel.moveItem(NavItem.FAVORITES, direction = -10)

		verify(exactly = 1) {
			settings.mainNavItems = listOf(NavItem.FAVORITES, NavItem.HOME, NavItem.HISTORY)
		}
	}

	@Test
	fun `navigation accepts up to five unique buttons`() {
		val settings = mockk<AppSettings>(relaxed = true) {
			every { mainNavItems } returns listOf(NavItem.HOME)
		}
		val viewModel = NavConfigViewModel(settings, mockk<ActivityRecreationHandle>(relaxed = true))

		viewModel.addItem(NavItem.HISTORY)
		viewModel.addItem(NavItem.FAVORITES)
		viewModel.addItem(NavItem.EXPLORE)
		viewModel.addItem(NavItem.FEED)
		viewModel.addItem(NavItem.BOOKMARKS)

		val persistedItems = mutableListOf<List<NavItem>>()
		verify(exactly = 4) { settings.mainNavItems = capture(persistedItems) }
		assertEquals(
			listOf(NavItem.HOME, NavItem.HISTORY, NavItem.FAVORITES, NavItem.EXPLORE, NavItem.FEED),
			persistedItems.last(),
		)
	}

	@Test
	fun `pending recreation fires when the view model is cleared`() {
		val settings = mockk<AppSettings>(relaxed = true) {
			every { mainNavItems } returns listOf(NavItem.HOME)
		}
		val recreation = mockk<ActivityRecreationHandle>(relaxed = true)
		val viewModel = NavConfigViewModel(settings, recreation)
		val store = ViewModelStore()
		store.put("nav", viewModel)

		viewModel.addItem(NavItem.HISTORY)
		// Debounce has not elapsed yet: no recreation so far.
		verify(exactly = 0) { recreation.recreate(MainActivity::class.java) }

		store.clear() // fragment destroyed / settings finished within the debounce window

		verify(exactly = 1) { recreation.recreate(MainActivity::class.java) }
	}
}
