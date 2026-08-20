package org.skepsun.kototoro.video.data

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class VideoDownloadIndexCompatibilityTest {

	@TempDir
	lateinit var tempDir: File

	@Test
	fun `reads absolute path stored by legacy download index`() {
		val downloadedFile = File(tempDir, "episode.mp4").apply { writeText("video") }
		val preferences = mockk<SharedPreferences>()
		val context = mockk<Context>()
		val uri = mockk<Uri>()
		mockkStatic(Uri::class)
		try {
			every { context.getSharedPreferences("video_download_index", Context.MODE_PRIVATE) } returns preferences
			every { preferences.getString("12:34", null) } returns downloadedFile.absolutePath
			every { Uri.fromFile(any()) } returns uri
			every { uri.scheme } returns "file"
			every { uri.path } returns downloadedFile.absolutePath

			val restored = VideoDownloadIndex(context).getFile(mangaId = 12, chapterId = 34)

			assertEquals(downloadedFile, restored)
		} finally {
			unmockkStatic(Uri::class)
		}
	}
}
