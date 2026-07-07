package org.skepsun.kototoro.local.domain.model

import android.net.Uri
import androidx.core.net.toFile
import androidx.core.net.toUri
import org.skepsun.kototoro.core.util.ext.contains
import org.skepsun.kototoro.core.util.ext.creationTime
import org.skepsun.kototoro.parsers.model.Content
import org.skepsun.kototoro.parsers.model.ContentTag
import java.io.File

data class LocalContent(
	val manga: Content,
	val file: File = manga.url.toUri().let { uri ->
		if (uri.scheme == "file") {
			File(requireNotNull(uri.path) { "File uri path is null: $uri" })
		} else {
			File(uri.schemeSpecificPart)
		}
	},
) {

	var createdAt: Long = -1L
		private set
		get() {
			if (field == -1L) {
				field = file.creationTime
			}
			return field
		}

	fun toUri(): Uri = manga.url.toUri()

	fun isMatchesQuery(query: String): Boolean {
		return manga.title.contains(query, ignoreCase = true) ||
			manga.altTitles.contains(query, ignoreCase = true) ||
			manga.authors.contains(query, ignoreCase = true)
	}

	fun containsTags(tags: Collection<String>): Boolean {
		return tags.all { tag -> tag in manga.tags }
	}

	fun containsAnyTag(tags: Collection<String>): Boolean {
		return tags.any { tag -> tag in manga.tags }
	}

	private operator fun Collection<ContentTag>.contains(title: String): Boolean {
		return any { it.title.equals(title, ignoreCase = true) }
	}

	override fun toString(): String {
		return "LocalContent(${file.path}: ${manga.title})"
	}
}
