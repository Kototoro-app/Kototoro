package org.skepsun.kototoro.parsers.util

import org.json.JSONArray
import org.json.JSONObject
import org.skepsun.kototoro.parsers.InternalParsersApi
import org.skepsun.kototoro.parsers.model.ContentChapter
import org.skepsun.kototoro.parsers.util.json.asTypedList

@InternalParsersApi
public inline fun <T> List<T>.mapChapters(
	reversed: Boolean = false,
	transform: (index: Int, T) -> ContentChapter?,
): List<ContentChapter> {
	val builder = ChaptersListBuilder(collectionSize())
	var index = 0
	val elements = if (reversed) this.asReversed() else this
	for (item in elements) {
		if (builder.add(transform(index, item))) {
			index++
		}
	}
	return builder.toList()
}

@InternalParsersApi
public inline fun JSONArray.mapChapters(
	reversed: Boolean = false,
	transform: (index: Int, JSONObject) -> ContentChapter?,
): List<ContentChapter> = asTypedList<JSONObject>().mapChapters(reversed, transform)

@InternalParsersApi
public inline fun <T> List<T>.flatMapChapters(
	reversed: Boolean = false,
	transform: (T) -> Iterable<ContentChapter?>,
): List<ContentChapter> {
	val builder = ChaptersListBuilder(collectionSize())
	val elements = if (reversed) this.asReversed() else this
	for (item in elements) {
		builder.addAll(transform(item))
	}
	return builder.toList()
}

@PublishedApi
internal fun <T> Iterable<T>.collectionSize(): Int {
	return if (this is Collection<*>) this.size else 10
}

@PublishedApi
internal class ChaptersListBuilder(initialSize: Int) {

	private val ids = HashSet<Long>(initialSize)
	private val list = ArrayList<ContentChapter>(initialSize)

	fun add(chapter: ContentChapter?): Boolean {
		return chapter != null && ids.add(chapter.id) && list.add(chapter)
	}

	fun addAll(chapters: Iterable<ContentChapter?>) {
		if (chapters is Collection<*>) {
			list.ensureCapacity(list.size + chapters.size)
		}
		chapters.forEach { add(it) }
	}

	operator fun plusAssign(chapter: ContentChapter?) {
		add(chapter)
	}

	fun reverse() {
		list.reverse()
	}

	fun toList(): List<ContentChapter> = list
}
