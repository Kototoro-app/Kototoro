package org.skepsun.kototoro.history.ui

import androidx.paging.PagingData
import androidx.paging.testing.asSnapshot
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.list.ui.model.ListHeader
import org.skepsun.kototoro.list.ui.model.ListModel

class HistoryListViewModelPagingTest {

	@Test
	fun `date headers are inserted without collecting paging data in the view model`() = runTest {
		val firstHeader = ListHeader("Today")
		val secondHeader = ListHeader("Yesterday")
		val first = TestItem(1)
		val second = TestItem(2)
		val third = TestItem(3)

		val snapshot = flowOf(PagingData.from<ListModel>(listOf(first, second, third)))
			.map { pagingData ->
				pagingData.applyHistoryPagingPresentation(
					grouped = true,
					headerItem = null,
					headerFor = { item ->
						when ((item as TestItem).id) {
							1, 2 -> firstHeader
							else -> secondHeader
						}
					},
				)
			}
			.asSnapshot()

		assertEquals(listOf(firstHeader, first, second, secondHeader, third), snapshot)
	}

	private data class TestItem(val id: Int) : ListModel {
		override fun areItemsTheSame(other: ListModel): Boolean = other is TestItem && other.id == id
	}
}
