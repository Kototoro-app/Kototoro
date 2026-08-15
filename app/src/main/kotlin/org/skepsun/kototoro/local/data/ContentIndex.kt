package org.skepsun.kototoro.local.data

import androidx.annotation.WorkerThread
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toOkioPath
import okio.buffer
import org.jetbrains.annotations.Blocking
import org.json.JSONArray
import org.json.JSONObject
import org.skepsun.kototoro.BuildConfig
import org.skepsun.kototoro.core.model.LocalMangaSource
import org.skepsun.kototoro.core.model.LocalNovelSource
import org.skepsun.kototoro.core.model.LocalVideoSource
import org.skepsun.kototoro.core.model.ContentSource as createContentSource
import org.skepsun.kototoro.core.model.looksLikeVideoUrl
import org.skepsun.kototoro.core.util.ext.printStackTraceDebug
import org.skepsun.kototoro.parsers.model.ContentRating
import org.skepsun.kototoro.parsers.model.Content
import org.skepsun.kototoro.parsers.model.ContentChapter
import org.skepsun.kototoro.parsers.model.ContentSource
import org.skepsun.kototoro.parsers.model.ContentState
import org.skepsun.kototoro.parsers.model.ContentTag
import org.skepsun.kototoro.parsers.model.RATING_UNKNOWN
import org.skepsun.kototoro.parsers.util.json.getBooleanOrDefault
import org.skepsun.kototoro.parsers.util.json.getEnumValueOrNull
import org.skepsun.kototoro.parsers.util.json.getFloatOrDefault
import org.skepsun.kototoro.parsers.util.json.getIntOrDefault
import org.skepsun.kototoro.parsers.util.json.getLongOrDefault
import org.skepsun.kototoro.parsers.util.json.getStringOrNull
import org.skepsun.kototoro.parsers.util.json.mapJSONToSet
import org.skepsun.kototoro.parsers.util.json.toStringSet
import org.skepsun.kototoro.parsers.util.runCatchingCancellable
import org.skepsun.kototoro.parsers.util.toTitleCase
import java.io.File

class ContentIndex(source: String?) {

	private val json: JSONObject = source?.let(::JSONObject) ?: JSONObject()

	fun setContentInfo(manga: Content) {
		json.put(KEY_ID, manga.id)
		json.put(KEY_TITLE, manga.title)
		json.put(KEY_TITLE_ALT, manga.altTitles.ifEmpty { null }?.firstOrNull()) // for backward compatibility
		json.put(KEY_ALT_TITLES, JSONArray(manga.altTitles))
		json.put(KEY_URL, manga.url)
		json.put(KEY_PUBLIC_URL, manga.publicUrl)
		json.put(KEY_AUTHOR, manga.authors.ifEmpty { null }?.firstOrNull()) // for backward compatibility
		json.put(KEY_AUTHORS, JSONArray(manga.authors))
		json.put(KEY_COVER, manga.coverUrl)
		json.put(KEY_COVER_LARGE, manga.largeCoverUrl)
		json.put(KEY_DESCRIPTION, manga.description)
		json.put(KEY_RATING, manga.rating)
		val contentRating = manga.contentRating
		if (contentRating != null) {
			json.put(KEY_CONTENT_RATING, contentRating.name)
		}
		json.put(KEY_NSFW, manga.isNsfw) // for backward compatibility
		val state = manga.state
		if (state != null) {
			json.put(KEY_STATE, state.name)
		}
		json.put(KEY_SOURCE, manga.source.name)
		json.put(
			KEY_TAGS,
			JSONArray().also { a ->
				for (tag in manga.tags) {
					val jo = JSONObject()
					jo.put(KEY_KEY, tag.key)
					jo.put(KEY_TITLE, tag.title)
					a.put(jo)
				}
			},
		)
		if (!json.has(KEY_CHAPTERS)) {
			json.put(KEY_CHAPTERS, JSONObject())
		}
		json.put(KEY_APP_ID, BuildConfig.APPLICATION_ID)
		json.put(KEY_APP_VERSION, BuildConfig.VERSION_CODE)
		
		manga.chapters?.let { chapters ->
			val chaptersJson = json.optJSONObject(KEY_CHAPTERS) ?: JSONObject().also { json.put(KEY_CHAPTERS, it) }
			chapters.forEachIndexed { index, chapter ->
				val chapterIdStr = chapter.id.toString()
				if (!chaptersJson.has(chapterIdStr)) {
					val jo = JSONObject()
					jo.put(KEY_NUMBER, chapter.number)
					jo.put(KEY_ORDER, index + 1)
					jo.put(KEY_VOLUME, chapter.volume)
					jo.put(KEY_URL, chapter.url)
					jo.put(KEY_NAME, chapter.title.orEmpty())
					jo.put(KEY_UPLOAD_DATE, chapter.uploadDate)
					jo.put(KEY_SCANLATOR, chapter.scanlator)
					jo.put(KEY_BRANCH, chapter.branch)
					jo.put(KEY_SOURCE, chapter.source.name)
					// Note: KEY_FILE and KEY_ENTRIES are not set here as it's not downloaded yet
					chaptersJson.put(chapterIdStr, jo)
				} else {
					// Update existing entry with potentially new info (e.g. title, number)
					// but keep KEY_FILE and KEY_ENTRIES if present
					val jo = chaptersJson.getJSONObject(chapterIdStr)
					jo.put(KEY_NUMBER, chapter.number)
					jo.put(KEY_ORDER, index + 1)
					jo.put(KEY_VOLUME, chapter.volume)
					jo.put(KEY_URL, chapter.url)
					jo.put(KEY_NAME, chapter.title.orEmpty())
					jo.put(KEY_UPLOAD_DATE, chapter.uploadDate)
					jo.put(KEY_SCANLATOR, chapter.scanlator)
					jo.put(KEY_BRANCH, chapter.branch)
					if (!jo.has(KEY_SOURCE)) {
						jo.put(KEY_SOURCE, chapter.source.name)
					}
				}
			}
		}
	}

	fun getContentInfo(): Content? = if (json.length() == 0) null else runCatching {
		val rawSource = createContentSource(json.getStringOrNull(KEY_SOURCE))
		val source = rawSource.recoverLocalVideoSource(
			url = json.getString(KEY_URL),
			publicUrl = json.getStringOrNull(KEY_PUBLIC_URL),
			chaptersJson = json.optJSONObject(KEY_CHAPTERS),
		)
		Content(
			id = json.getLong(KEY_ID),
			title = json.getString(KEY_TITLE),
			altTitles = json.optJSONArray(KEY_ALT_TITLES)?.toStringSet()
				?: setOfNotNull(json.getStringOrNull(KEY_TITLE_ALT)),
			url = json.getString(KEY_URL),
			publicUrl = json.getStringOrNull(KEY_PUBLIC_URL).orEmpty(),
			authors = json.optJSONArray(KEY_AUTHORS)?.toStringSet()
				?: setOfNotNull(json.getStringOrNull(KEY_AUTHOR)),
			largeCoverUrl = json.getStringOrNull(KEY_COVER_LARGE),
			source = source,
			rating = json.getFloatOrDefault(KEY_RATING, RATING_UNKNOWN),
			contentRating = json.getEnumValueOrNull(KEY_CONTENT_RATING, ContentRating::class.java)
				?: if (json.getBooleanOrDefault(KEY_NSFW, false)) ContentRating.ADULT else null,
			coverUrl = json.getStringOrNull(KEY_COVER),
			state = json.getEnumValueOrNull(KEY_STATE, ContentState::class.java),
			description = json.getStringOrNull(KEY_DESCRIPTION),
			tags = json.getJSONArray(KEY_TAGS).mapJSONToSet { x ->
				ContentTag(
					title = x.getString(KEY_TITLE).toTitleCase(),
					key = x.getString(KEY_KEY),
					source = source,
				)
			},
			chapters = getChapters(source),
		)
	}.getOrNull()

	fun getCoverEntry(): String? = json.getStringOrNull(KEY_COVER_ENTRY)

	fun addChapter(
		chapter: IndexedValue<ContentChapter>,
		filename: String?,
		remoteImages: Map<String, String>? = null,
	) {
		val chapters = json.getJSONObject(KEY_CHAPTERS)
		val chapterIdStr = chapter.value.id.toString()
		if (!chapters.has(chapterIdStr)) {
			val jo = JSONObject()
			jo.put(KEY_NUMBER, chapter.value.number)
			jo.put(KEY_ORDER, chapter.index + 1)
			jo.put(KEY_VOLUME, chapter.value.volume)
			jo.put(KEY_URL, chapter.value.url)
			jo.put(KEY_NAME, chapter.value.title.orEmpty())
			jo.put(KEY_UPLOAD_DATE, chapter.value.uploadDate)
			jo.put(KEY_SCANLATOR, chapter.value.scanlator)
			jo.put(KEY_BRANCH, chapter.value.branch)
			jo.put(KEY_SOURCE, chapter.value.source.name)
			jo.put(KEY_ENTRIES, "%08d_%04d\\d{4}".format(chapter.value.branch.hashCode(), chapter.index + 1))
			jo.put(KEY_FILE, filename)
			putImagesInternal(jo, remoteImages)
			chapters.put(chapterIdStr, jo)
		} else {
			val jo = chapters.getJSONObject(chapterIdStr)
			if (!jo.has(KEY_ENTRIES)) {
				jo.put(KEY_ENTRIES, "%08d_%04d\\d{4}".format(chapter.value.branch.hashCode(), chapter.index + 1))
			}
			if (!filename.isNullOrBlank() && jo.optString(KEY_FILE).isNullOrBlank()) {
				jo.put(KEY_FILE, filename)
			}
			if (!jo.has(KEY_ORDER)) {
				jo.put(KEY_ORDER, chapter.index + 1)
			}
			if (!jo.has(KEY_SOURCE)) {
				jo.put(KEY_SOURCE, chapter.value.source.name)
			}
		}
	}

	fun putChapterImages(chapterId: Long, remoteImages: Map<String, String>) {
		val chapters = json.optJSONObject(KEY_CHAPTERS) ?: return
		val jo = chapters.optJSONObject(chapterId.toString()) ?: return
		putImagesInternal(jo, remoteImages)
	}

	private fun putImagesInternal(jo: JSONObject, remoteImages: Map<String, String>?) {
		remoteImages?.takeIf { it.isNotEmpty() }?.let { map ->
			val arr = JSONArray()
			for ((remote, local) in map) {
				arr.put(JSONObject().apply {
					put("remote", remote)
					put("local", local)
				})
			}
			jo.put("images", arr)
		}
	}

	fun getChapterImages(chapterId: Long): Map<String, String> {
		val chapters = json.optJSONObject(KEY_CHAPTERS) ?: return emptyMap()
		val jo = chapters.optJSONObject(chapterId.toString()) ?: return emptyMap()
		val arr = jo.optJSONArray("images") ?: return emptyMap()
		val result = LinkedHashMap<String, String>(arr.length())
		for (i in 0 until arr.length()) {
			val o = arr.optJSONObject(i) ?: continue
			val remote = o.optString("remote")
			val local = o.optString("local")
			if (remote.isNotBlank() && local.isNotBlank()) {
				result[remote] = local
			}
		}
		return result
	}

	fun removeChapter(id: Long): Boolean {
		val chapters = json.optJSONObject(KEY_CHAPTERS) ?: return false
		val idStr = id.toString()
		val jo = chapters.optJSONObject(idStr) ?: return false
		
		val mangaSource = json.optString(KEY_SOURCE)
		val isPurelyLocal = mangaSource == LocalMangaSource.name || mangaSource == LocalNovelSource.name || mangaSource == org.skepsun.kototoro.core.model.LocalVideoSource.name
		
		return if (isPurelyLocal) {
			chapters.remove(idStr) != null
		} else {
			jo.remove(KEY_FILE)
			jo.remove(KEY_ENTRIES)
			true
		}
	}

	fun getChapterFileName(chapterId: Long): String? {
		val value = json.optJSONObject(KEY_CHAPTERS)
			?.optJSONObject(chapterId.toString())
			?.opt(KEY_FILE)
		return value?.takeUnless { it === JSONObject.NULL }?.toString()
	}

	fun hasChapterEntries(chapterId: Long): Boolean {
		val jo = json.optJSONObject(KEY_CHAPTERS)?.optJSONObject(chapterId.toString()) ?: return false
		return jo.has(KEY_ENTRIES) || jo.has(KEY_ORDER)
	}

	fun setCoverEntry(name: String) {
		json.put(KEY_COVER_ENTRY, name)
	}

	fun getChapterNamesPattern(chapter: ContentChapter): Regex {
		val jo = json.getJSONObject(KEY_CHAPTERS).getJSONObject(chapter.id.toString())
		val entries = jo.optString(KEY_ENTRIES).ifBlank {
			val order = jo.optInt(KEY_ORDER, 1)
			"%08d_%04d\\d{4}".format(chapter.branch.hashCode(), order)
		}
		return Regex(entries)
	}

	fun sortChaptersByName() {
		val jo = json.getJSONObject(KEY_CHAPTERS)
		val list = ArrayList<JSONObject>(jo.length())
		jo.keys().forEach { id ->
			val item = jo.getJSONObject(id)
			item.put(KEY_ID, id)
			list.add(item)
		}
		val comparator = org.skepsun.kototoro.core.util.AlphanumComparator()
		list.sortWith(compareBy(comparator) { it.getString(KEY_NAME) })
		val newJo = JSONObject()
		list.forEachIndexed { i, obj ->
			obj.put(KEY_NUMBER, i + 1)
			val id = obj.remove(KEY_ID) as String
			newJo.put(id, obj)
		}
		json.put(KEY_CHAPTERS, newJo)
	}

	fun clear() {
		val keys = json.keys()
		while (keys.hasNext()) {
			json.remove(keys.next())
		}
	}

	fun setFrom(other: ContentIndex) {
		clear()
		other.json.keys().forEach { key ->
			json.putOpt(key, other.json.opt(key))
		}
	}

	fun getChapters(source: ContentSource): List<ContentChapter> {
		val chaptersJson = json.optJSONObject(KEY_CHAPTERS) ?: return emptyList()
		val chapters = ArrayList<ContentChapter>(chaptersJson.length())
		for (k in chaptersJson.keys()) {
			val v = chaptersJson.getJSONObject(k)
			val chapterSource = (v.getStringOrNull(KEY_SOURCE)?.let { createContentSource(it) } ?: source)
				.recoverLocalVideoSource(
					url = v.getString(KEY_URL),
					publicUrl = null,
					chaptersJson = null,
				)
			chapters.add(
				ContentChapter(
					id = k.toLong(),
					title = v.getStringOrNull(KEY_NAME),
					url = v.getString(KEY_URL),
					number = v.getFloatOrDefault(KEY_NUMBER, 0f),
					volume = v.getIntOrDefault(KEY_VOLUME, 0),
					uploadDate = v.getLongOrDefault(KEY_UPLOAD_DATE, 0L),
					scanlator = v.getStringOrNull(KEY_SCANLATOR),
					branch = v.getStringOrNull(KEY_BRANCH),
					source = chapterSource,
				),
			)
		}
		return chapters.sortedWith(compareBy { chapter ->
			val order = chaptersJson
				.optJSONObject(chapter.id.toString())
				?.optInt(KEY_ORDER, Int.MAX_VALUE)
				?: Int.MAX_VALUE
			if (order != Int.MAX_VALUE) order.toFloat() else chapter.number
		})
	}

	override fun toString(): String = if (BuildConfig.DEBUG) {
		json.toString(4)
	} else {
		json.toString()
	}

	fun addHiddenChapterId(chapterId: Long) {
		val hiddenIds = json.optJSONArray(KEY_HIDDEN_CHAPTERS) ?: JSONArray().also {
			json.put(KEY_HIDDEN_CHAPTERS, it)
		}
		// Check if not already present
		for (i in 0 until hiddenIds.length()) {
			if (hiddenIds.getLong(i) == chapterId) {
				return
			}
		}
		hiddenIds.put(chapterId)
	}

	fun getHiddenChapterIds(): Set<Long> {
		val hiddenIds = json.optJSONArray(KEY_HIDDEN_CHAPTERS) ?: return emptySet()
		val result = mutableSetOf<Long>()
		for (i in 0 until hiddenIds.length()) {
			result.add(hiddenIds.getLong(i))
		}
		return result
	}

	companion object {

		private const val KEY_ID = "id"
		private const val KEY_TITLE = "title"
		private const val KEY_TITLE_ALT = "title_alt"
		private const val KEY_ALT_TITLES = "alt_titles"
		private const val KEY_URL = "url"
		private const val KEY_PUBLIC_URL = "public_url"
		private const val KEY_AUTHOR = "author"
		private const val KEY_AUTHORS = "authors"
		private const val KEY_COVER = "cover"
		private const val KEY_DESCRIPTION = "description"
		private const val KEY_RATING = "rating"
		private const val KEY_CONTENT_RATING = "content_rating"
		private const val KEY_NSFW = "nsfw"
		private const val KEY_STATE = "state"
		private const val KEY_SOURCE = "source"
		private const val KEY_COVER_LARGE = "cover_large"
		private const val KEY_TAGS = "tags"
		private const val KEY_CHAPTERS = "chapters"
		private const val KEY_NUMBER = "number"
		private const val KEY_ORDER = "order"
		private const val KEY_VOLUME = "volume"
		private const val KEY_NAME = "name"
		private const val KEY_UPLOAD_DATE = "uploadDate"
		private const val KEY_SCANLATOR = "scanlator"
		private const val KEY_BRANCH = "branch"
		private const val KEY_ENTRIES = "entries"
		private const val KEY_FILE = "file"
		private const val KEY_COVER_ENTRY = "cover_entry"
		private const val KEY_KEY = "key"
		private const val KEY_APP_ID = "app_id"
		private const val KEY_APP_VERSION = "app_version"
		private const val KEY_HIDDEN_CHAPTERS = "hidden_chapters"

		@Blocking
		@WorkerThread
		fun read(fileSystem: FileSystem, path: Path): ContentIndex? = runCatchingCancellable {
			if (!fileSystem.exists(path)) {
				return@runCatchingCancellable null
			}
			val text = fileSystem.source(path).use {
				it.buffer().use { buffer ->
					buffer.readUtf8()
				}
			}
			if (text.length > 2) {
				ContentIndex(text)
			} else {
				null
			}
		}.onFailure { e ->
			e.printStackTraceDebug()
		}.getOrNull()

		@Blocking
		@WorkerThread
		fun read(file: File): ContentIndex? = read(FileSystem.SYSTEM, file.toOkioPath())
	}
}

private fun ContentSource.recoverLocalVideoSource(
	url: String?,
	publicUrl: String?,
	chaptersJson: JSONObject?,
): ContentSource {
	if (this != LocalMangaSource) {
		return this
	}
	if (url.looksLikeVideoUrl() || publicUrl.looksLikeVideoUrl()) {
		return LocalVideoSource
	}
	val chapters = chaptersJson ?: return this
	for (key in chapters.keys()) {
		val chapterUrl = chapters.optJSONObject(key)?.getStringOrNull("url")
		if (chapterUrl.looksLikeVideoUrl()) {
			return LocalVideoSource
		}
	}
	return this
}
