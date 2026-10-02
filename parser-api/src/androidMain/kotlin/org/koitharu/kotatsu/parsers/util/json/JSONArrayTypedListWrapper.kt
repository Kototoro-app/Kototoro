package org.koitharu.kotatsu.parsers.util.json

import org.json.JSONArray

internal class JSONArrayTypedListWrapper<T : Any>(
	private val jsonArray: JSONArray,
	private val typeClass: Class<T>,
) : List<T> {

	override fun contains(element: T): Boolean = indexOf(element) != -1

	override fun containsAll(elements: Collection<T>): Boolean = elements.all { contains(it) }

	override fun get(index: Int): T = typeClass.cast(jsonArray[index])

	override fun indexOf(element: T): Int {
		repeat(jsonArray.length()) { i ->
			if (jsonArray[i] == element) {
				return i
			}
		}
		return -1
	}

	override fun isEmpty(): Boolean = jsonArray.length() == 0

	override fun iterator(): Iterator<T> = listIterator(0)

	override fun lastIndexOf(element: T): Int {
		val total = jsonArray.length()
		repeat(total) { i ->
			if (jsonArray[total - i - 1] == element) {
				return i
			}
		}
		return -1
	}

	override fun listIterator(): ListIterator<T> = listIterator(0)

	override fun listIterator(index: Int): ListIterator<T> = JSONArrayTypedIterator(jsonArray, typeClass, index)

	override fun subList(fromIndex: Int, toIndex: Int): List<T> {
		val result = ArrayList<T>(toIndex - fromIndex + 1)
		for (i in fromIndex..toIndex) {
			result.add(get(i))
		}
		return result
	}

	override val size: Int
		get() = jsonArray.length()
}
