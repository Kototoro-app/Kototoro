package org.koitharu.kotatsu.parsers.util

public class Paginator internal constructor(private val initialPageSize: Int) {

	public var firstPage: Int = 1
	private var pages = HashMap<Int, Int>()

	internal fun getPage(offset: Int): Int {
		if (offset == 0) { // just an optimization
			return firstPage
		}
		pages[offset]?.let { return it }
		val pageSize = initialPageSize
		val intPage = offset / pageSize
		val tail = offset % pageSize
		return intPage + firstPage + if (tail == 0) 0 else 1
	}

	internal fun onListReceived(offset: Int, page: Int, count: Int) {
		pages[offset + count] = if (count > 0) page + 1 else page
	}
}
