package org.skepsun.kototoro.reader.ui.compose

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class ComposeReaderNavigationPolicyTest {

	@Test
	fun `page is not reported while finger is dragging pager`() {
		assertNull(
			resolveReaderPageToReport(
				isDragged = true,
				isScrollInProgress = true,
				settledPage = 2,
				targetPage = 3,
				isRestoringAnchor = false,
			),
		)
	}

	@Test
	fun `target page is reported when finger releases before animation settles`() {
		assertEquals(
			3,
			resolveReaderPageToReport(
				isDragged = false,
				isScrollInProgress = true,
				settledPage = 2,
				targetPage = 3,
				isRestoringAnchor = false,
			),
		)
	}

	@Test
	fun `settled page is reported when pager is idle`() {
		assertEquals(
			3,
			resolveReaderPageToReport(
				isDragged = false,
				isScrollInProgress = false,
				settledPage = 3,
				targetPage = 3,
				isRestoringAnchor = false,
			),
		)
	}

	@Test
	fun `anchor restoration does not report a transient page`() {
		assertNull(
			resolveReaderPageToReport(
				isDragged = false,
				isScrollInProgress = false,
				settledPage = 3,
				targetPage = 3,
				isRestoringAnchor = true,
			),
		)
	}
}
