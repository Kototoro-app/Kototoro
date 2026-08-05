package org.skepsun.kototoro.reader.ui.eink

internal data class EInkPageIdentity(
	val chapterId: Long,
	val pageIndex: Int,
)

internal class EInkRefreshPolicy {

	private var pageChangeCount = 0

	fun reset() {
		pageChangeCount = 0
	}

	fun shouldRefresh(
		enabled: Boolean,
		isPagedMode: Boolean,
		previous: EInkPageIdentity?,
		current: EInkPageIdentity?,
		interval: Int,
	): Boolean {
		if (!enabled || !isPagedMode) {
			reset()
			return false
		}
		if (previous == null || current == null) {
			reset()
			return false
		}
		if (previous == current) {
			return false
		}

		pageChangeCount++
		if (pageChangeCount < interval.coerceIn(MIN_INTERVAL, MAX_INTERVAL)) {
			return false
		}

		reset()
		return true
	}

	private companion object {
		const val MIN_INTERVAL = 1
		const val MAX_INTERVAL = 10
	}
}
