package org.skepsun.kototoro.download.ui.worker

import androidx.work.Data
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DownloadTaskDataTest {

	@Test
	fun `large chapter selection stays within WorkManager data limit`() {
		val chapterIds = LongArray(193) { it.toLong() + 1L }
		val chapterRefs = chapterIds.map { id ->
			ExecutionChapterRef(
				id = id,
				url = "https://example.com/chapter/$id/" + "abcdefghijklmnopqrstuvwxyz".repeat(4),
				title = "Chapter $id",
				number = id.toFloat(),
				volume = 1,
				branch = null,
			)
		}
		val task = DownloadTask.createExecutionTask(
			executionMangaId = 1L,
			isPaused = false,
			isSilent = false,
			executionChapterIds = chapterIds,
			executionChapterRefs = chapterRefs,
			destination = null,
			format = null,
			allowMeteredNetwork = true,
		)

		val data = task.toData()
		val restored = DownloadTask(data)

		assertTrue(data.toByteArray().size <= Data.MAX_DATA_BYTES)
		assertTrue(restored.executionChapterIds contentEquals chapterIds)
		assertEquals(chapterRefs, restored.executionChapterRefs)
	}

	@Test
	fun `oversized chapter refs fall back to chapter ids`() {
		val chapterIds = LongArray(193) { it.toLong() + 1L }
		val chapterRefs = chapterIds.map { id ->
			ExecutionChapterRef(
				id = id,
				url = buildString {
					append("https://example.com/chapter/")
					append(id)
					var value = id * -7046029254386353131L
					repeat(256) {
						value = value xor (value shl 13)
						value = value xor (value ushr 7)
						value = value xor (value shl 17)
						append(value.toString(36))
					}
				},
				title = "Chapter $id",
				number = id.toFloat(),
				volume = 1,
				branch = null,
			)
		}
		val task = DownloadTask.createExecutionTask(
			executionMangaId = 1L,
			isPaused = false,
			isSilent = false,
			executionChapterIds = chapterIds,
			executionChapterRefs = chapterRefs,
			destination = null,
			format = null,
			allowMeteredNetwork = true,
		)

		val data = task.toData()
		val restored = DownloadTask(data)

		assertTrue(data.toByteArray().size <= Data.MAX_DATA_BYTES)
		assertTrue(restored.executionChapterIds contentEquals chapterIds)
		assertEquals(null, restored.executionChapterRefs)
	}
}
