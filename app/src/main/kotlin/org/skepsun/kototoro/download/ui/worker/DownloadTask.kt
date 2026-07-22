package org.skepsun.kototoro.download.ui.worker

import android.os.Parcelable
import androidx.work.Data
import kotlinx.parcelize.Parcelize
import org.skepsun.kototoro.core.prefs.DownloadFormat
import org.skepsun.kototoro.parsers.model.ContentChapter
import org.skepsun.kototoro.parsers.util.find
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

@Parcelize
data class ExecutionChapterRef(
	val id: Long,
	val url: String,
	val title: String?,
	val number: Float,
	val volume: Int,
	val branch: String?,
) : Parcelable {

	companion object {

		fun fromChapter(chapter: ContentChapter): ExecutionChapterRef = ExecutionChapterRef(
			id = chapter.id,
			url = chapter.url,
			title = chapter.title,
			number = chapter.number,
			volume = chapter.volume,
			branch = chapter.branch,
		)
	}
}

@Parcelize
class DownloadTask(
	val mangaId: Long,
	val displayMangaId: Long? = null,
	val isPaused: Boolean,
	val isSilent: Boolean,
	val chaptersIds: LongArray?,
	val chapterRefs: List<ExecutionChapterRef>? = null,
	val destination: File?,
	val format: DownloadFormat?,
	val allowMeteredNetwork: Boolean,
	val preferredQuality: String? = null,
	val kind: DownloadTaskKind = DownloadTaskKind.DOWNLOAD,
) : Parcelable {

	val executionMangaId: Long
		get() = mangaId

	val executionChapterIds: LongArray?
		get() = chaptersIds

	val executionChapterRefs: List<ExecutionChapterRef>?
		get() = chapterRefs

	constructor(data: Data) : this(
		mangaId = data.getLong(MANGA_ID, 0L),
		displayMangaId = data.getLong(DISPLAY_MANGA_ID, 0L).takeIf { it != 0L },
		isPaused = data.getBoolean(START_PAUSED, false),
		isSilent = data.getBoolean(IS_SILENT, false),
		chaptersIds = data.getLongArray(CHAPTERS)?.takeUnless(LongArray::isEmpty),
		chapterRefs = data.getByteArray(CHAPTER_REFS)?.let(::decodeChapterRefs)
			?: data.getString(CHAPTER_REFS)?.let(::decodeChapterRefsJson),
		destination = data.getString(DESTINATION)?.let { File(it) },
		format = data.getString(FORMAT)?.let { DownloadFormat.entries.find(it) },
		allowMeteredNetwork = data.getBoolean(ALLOW_METERED, true),
		preferredQuality = data.getString(PREFERRED_QUALITY),
		kind = data.getString(KIND)?.let { DownloadTaskKind.entries.find(it) } ?: DownloadTaskKind.DOWNLOAD,
	)

	fun toData(): Data {
		val encodedChapterRefs = chapterRefs?.let(::encodeChapterRefs)
		if (encodedChapterRefs == null) {
			return createDataBuilder().build()
		}

		return try {
			val data = createDataBuilder(encodedChapterRefs).build()
			if (data.toByteArray().size <= Data.MAX_DATA_BYTES - DATA_SIZE_RESERVE_BYTES) {
				data
			} else {
				createDataBuilder().build()
			}
		} catch (e: IllegalStateException) {
			if (!e.message.orEmpty().contains(DATA_SIZE_ERROR)) {
				throw e
			}
			createDataBuilder().build()
		}
	}

	private fun createDataBuilder(encodedChapterRefs: ByteArray? = null): Data.Builder {
		return Data.Builder()
			.putLong(MANGA_ID, mangaId)
			.putLong(DISPLAY_MANGA_ID, displayMangaId ?: 0L)
			.putBoolean(START_PAUSED, isPaused)
			.putBoolean(IS_SILENT, isSilent)
			.putLongArray(CHAPTERS, chaptersIds ?: LongArray(0))
			.apply {
				if (encodedChapterRefs != null) {
					putByteArray(CHAPTER_REFS, encodedChapterRefs)
				}
			}
			.putString(DESTINATION, destination?.path)
			.putString(FORMAT, format?.name)
			.putBoolean(ALLOW_METERED, allowMeteredNetwork)
			.putString(PREFERRED_QUALITY, preferredQuality)
			.putString(KIND, kind.name)
	}

	override fun equals(other: Any?): Boolean {
		if (this === other) return true
		if (javaClass != other?.javaClass) return false

		other as DownloadTask

		if (mangaId != other.mangaId) return false
		if (displayMangaId != other.displayMangaId) return false
		if (isPaused != other.isPaused) return false
		if (isSilent != other.isSilent) return false
		if (!(chaptersIds contentEquals other.chaptersIds)) return false
		if (chapterRefs != other.chapterRefs) return false
		if (destination != other.destination) return false
		if (format != other.format) return false
		if (allowMeteredNetwork != other.allowMeteredNetwork) return false
		if (preferredQuality != other.preferredQuality) return false
		if (kind != other.kind) return false

		return true
	}

	override fun hashCode(): Int {
		var result = mangaId.hashCode()
		result = 31 * result + (displayMangaId?.hashCode() ?: 0)
		result = 31 * result + isPaused.hashCode()
		result = 31 * result + isSilent.hashCode()
		result = 31 * result + (chaptersIds?.contentHashCode() ?: 0)
		result = 31 * result + (chapterRefs?.hashCode() ?: 0)
		result = 31 * result + (destination?.hashCode() ?: 0)
		result = 31 * result + (format?.hashCode() ?: 0)
		result = 31 * result + allowMeteredNetwork.hashCode()
		result = 31 * result + (preferredQuality?.hashCode() ?: 0)
		result = 31 * result + kind.hashCode()
		return result
	}

	companion object {

		fun createExecutionTask(
			executionMangaId: Long,
			displayMangaId: Long? = null,
			isPaused: Boolean,
			isSilent: Boolean,
			executionChapterIds: LongArray?,
			executionChapterRefs: List<ExecutionChapterRef>? = null,
			destination: File?,
			format: DownloadFormat?,
			allowMeteredNetwork: Boolean,
			preferredQuality: String? = null,
			kind: DownloadTaskKind = DownloadTaskKind.DOWNLOAD,
		): DownloadTask = DownloadTask(
			mangaId = executionMangaId,
			displayMangaId = displayMangaId,
			isPaused = isPaused,
			isSilent = isSilent,
			chaptersIds = executionChapterIds,
			chapterRefs = executionChapterRefs,
			destination = destination,
			format = format,
			allowMeteredNetwork = allowMeteredNetwork,
			preferredQuality = preferredQuality,
			kind = kind,
		)

		const val MANGA_ID = "manga_id"
		const val DISPLAY_MANGA_ID = "display_manga_id"
		const val IS_SILENT = "silent"
		const val START_PAUSED = "paused"
		const val CHAPTERS = "chapters"
		const val CHAPTER_REFS = "chapter_refs"
		const val DESTINATION = "dest"
		const val FORMAT = "format"
		const val ALLOW_METERED = "metered"
		const val PREFERRED_QUALITY = "preferred_quality"
		const val KIND = "kind"

		private fun encodeChapterRefs(refs: List<ExecutionChapterRef>): ByteArray {
			val json = encodeChapterRefsJson(refs)
			return ByteArrayOutputStream().use { output ->
				GZIPOutputStream(output).use { gzip ->
					gzip.write(json.toByteArray(Charsets.UTF_8))
				}
				output.toByteArray()
			}
		}

		private fun encodeChapterRefsJson(refs: List<ExecutionChapterRef>): String {
			return JSONArray().apply {
				refs.forEach { ref ->
					put(
						JSONObject().apply {
							put("id", ref.id)
							put("url", ref.url)
							put("title", ref.title)
							put("number", ref.number.toDouble())
							put("volume", ref.volume)
							put("branch", ref.branch)
						},
					)
				}
			}.toString()
		}

		private fun decodeChapterRefs(data: ByteArray): List<ExecutionChapterRef> =
			GZIPInputStream(ByteArrayInputStream(data)).bufferedReader(Charsets.UTF_8).use { reader ->
				decodeChapterRefsJson(reader.readText())
			}

		private fun decodeChapterRefsJson(raw: String): List<ExecutionChapterRef> {
			if (raw.isBlank()) {
				return emptyList()
			}
			val array = JSONArray(raw)
			return buildList(array.length()) {
				for (index in 0 until array.length()) {
					val item = array.optJSONObject(index) ?: continue
					add(
						ExecutionChapterRef(
							id = item.optLong("id"),
							url = item.optString("url"),
							title = item.optString("title").takeIf { it.isNotBlank() },
							number = item.optDouble("number", 0.0).toFloat(),
							volume = item.optInt("volume", 0),
							branch = item.optString("branch").takeIf { it.isNotBlank() },
						),
					)
				}
			}
		}

		private const val DATA_SIZE_ERROR = "Data cannot occupy more than"
		private const val DATA_SIZE_RESERVE_BYTES = 512
	}
}
