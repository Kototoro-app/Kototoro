package org.koitharu.kotatsu.parsers.util.json

import org.json.JSONArray

internal class JSONArrayTypedIterator<T : Any>(
	private val array: JSONArray,
	private val typeClass: Class<T>,
	startIndex: Int,
) : ListIterator<T> {

	private val total = array.length()
	private var index = startIndex

	override fun hasNext() = index < total

	override fun next(): T {
		if (!hasNext()) throw NoSuchElementException()
		return get(index++)
	}

	override fun hasPrevious(): Boolean = index > 0

	override fun nextIndex(): Int = index

	override fun previous(): T {
		if (!hasPrevious()) throw NoSuchElementException()
		return get(--index)
	}

	override fun previousIndex(): Int = index - 1

	private fun get(i: Int): T = typeClass.cast(array[i])
}
