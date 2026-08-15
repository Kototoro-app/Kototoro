package org.skepsun.kototoro.local.data.output

import com.hippo.unifile.UniFile
import org.skepsun.kototoro.parsers.model.Content
import java.io.File

class LocalContentUtil(
	private val manga: Content,
	private val file: UniFile,
	private val cacheDir: File,
) {

	suspend fun deleteChapters(ids: Set<Long>) {
		if (file.isDirectory) {
			LocalContentDirOutput(file, manga, cacheDir).use { output ->
				output.deleteChapters(ids)
				output.finish()
			}
		} else {
			LocalContentZipOutput.filterChapters(file, manga, ids, cacheDir)
		}
	}
}
