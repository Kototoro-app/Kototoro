package org.skepsun.kototoro.core.jsonsource

import org.skepsun.kototoro.core.db.entity.JsonSourceType
import org.skepsun.kototoro.core.model.jsonsource.TVBoxStoredConfig
import java.net.URI

data class TVBoxRepositoryOption(
	val id: String,
	val title: String,
)

data class TVBoxRepositorySelection(
	val options: List<TVBoxRepositoryOption> = emptyList(),
	val activeId: String? = null,
) {
	val active: TVBoxRepositoryOption?
		get() = options.firstOrNull { it.id == activeId }
}

internal object TVBoxRepositorySelector {

	private const val LEGACY_REPOSITORY_ID = "tvbox:legacy"

	fun resolve(
		options: Iterable<TVBoxRepositoryOption>,
		preferredId: String?,
	): TVBoxRepositorySelection {
		val distinctOptions = options
			.distinctBy(TVBoxRepositoryOption::id)
			.sortedWith(compareBy<TVBoxRepositoryOption> { it.title }.thenBy { it.id })
		val activeId = preferredId?.takeIf { preferred -> distinctOptions.any { it.id == preferred } }
			?: distinctOptions.firstOrNull()?.id
		return TVBoxRepositorySelection(distinctOptions, activeId)
	}

	fun option(type: JsonSourceType, config: String): TVBoxRepositoryOption? {
		if (type != JsonSourceType.TVBOX) return null
		val stored = runCatching { TVBoxStoredConfig.parse(config) }.getOrNull()
		val locator = stored?.meta?.sourceLocator?.trim()?.takeIf(String::isNotBlank)
		val sourceTitle = stored?.meta?.sourceTitle?.trim()?.takeIf(String::isNotBlank)
		return TVBoxRepositoryOption(
			id = locator ?: sourceTitle?.let { "tvbox:title:$it" } ?: LEGACY_REPOSITORY_ID,
			title = sourceTitle ?: locator?.toRepositoryTitle() ?: "TVBox",
		)
	}

	fun isVisible(type: JsonSourceType, config: String, activeId: String?): Boolean {
		if (type != JsonSourceType.TVBOX) return true
		return option(type, config)?.id == activeId
	}

	private fun String.toRepositoryTitle(): String {
		return runCatching {
			val uri = URI(this)
			val host = uri.host?.takeIf(String::isNotBlank)
			val tail = uri.path?.substringAfterLast('/')?.takeIf(String::isNotBlank)
			when {
				host != null && tail != null -> "$host - $tail"
				host != null -> host
				else -> substringAfterLast('/').ifBlank { this }
			}
		}.getOrElse { substringAfterLast('/').ifBlank { this } }
	}
}
