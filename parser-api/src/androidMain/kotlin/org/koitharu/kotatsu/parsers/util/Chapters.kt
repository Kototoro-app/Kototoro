package org.koitharu.kotatsu.parsers.util

import org.json.JSONArray
import org.json.JSONObject
import org.koitharu.kotatsu.parsers.InternalParsersApi
import org.koitharu.kotatsu.parsers.model.MangaChapter
import org.koitharu.kotatsu.parsers.util.json.asTypedList

@InternalParsersApi
public inline fun <T> List<T>.mapChapters(
	reversed: Boolean = false,
	transform: (index: Int, T) -> MangaChapter?,
): List<MangaChapter> {
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
	transform: (index: Int, JSONObject) -> MangaChapter?,
): List<MangaChapter> = asTypedList<JSONObject>().mapChapters(reversed, transform)

@InternalParsersApi
public inline fun <T> List<T>.flatMapChapters(
	reversed: Boolean = false,
	transform: (T) -> Iterable<MangaChapter?>,
): List<MangaChapter> {
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
	private val list = ArrayList<MangaChapter>(initialSize)

	fun add(chapter: MangaChapter?): Boolean {
		return chapter != null && ids.add(chapter.id) && list.add(chapter)
	}

	fun addAll(chapters: Iterable<MangaChapter?>) {
		if (chapters is Collection<*>) {
			list.ensureCapacity(list.size + chapters.size)
		}
		chapters.forEach { add(it) }
	}

	operator fun plusAssign(chapter: MangaChapter?) {
		add(chapter)
	}

	fun reverse() {
		list.reverse()
	}

	fun toList(): List<MangaChapter> = list
}
