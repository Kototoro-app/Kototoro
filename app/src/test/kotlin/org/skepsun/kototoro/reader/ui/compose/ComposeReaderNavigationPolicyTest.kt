package org.skepsun.kototoro.reader.ui.compose

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ComposeReaderNavigationPolicyTest {

	@Test
	fun `page is not reported while pager animation is running`() {
		assertFalse(
			shouldReportReaderSettledPage(
				isScrollInProgress = true,
				isRestoringAnchor = false,
			),
		)
	}

	@Test
	fun `page is reported only after pager settles`() {
		assertTrue(
			shouldReportReaderSettledPage(
				isScrollInProgress = false,
				isRestoringAnchor = false,
			),
		)
	}

	@Test
	fun `anchor restoration does not report a transient page`() {
		assertFalse(
			shouldReportReaderSettledPage(
				isScrollInProgress = false,
				isRestoringAnchor = true,
			),
		)
	}
}
