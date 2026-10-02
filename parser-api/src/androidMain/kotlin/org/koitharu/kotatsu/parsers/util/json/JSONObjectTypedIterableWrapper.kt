package org.koitharu.kotatsu.parsers.util.json

import org.json.JSONObject

internal class JSONObjectTypedIterableWrapper<T : Any>(
	private val json: JSONObject,
	private val typeClass: Class<T>,
) : Iterable<Map.Entry<String, T>> {

	override fun iterator(): Iterator<Map.Entry<String, T>> = IteratorImpl()

	private inner class IteratorImpl : Iterator<Map.Entry<String, T>> {

		private val keyIterator = json.keys().iterator()

		override fun hasNext(): Boolean = keyIterator.hasNext()

		override fun next(): Map.Entry<String, T> = keyIterator.next().let { key ->
			JSONEntry(key, typeClass.cast(json.get(key)))
		}
	}

	private class JSONEntry<T : Any>(override val key: String, override val value: T) : Map.Entry<String, T>
}
