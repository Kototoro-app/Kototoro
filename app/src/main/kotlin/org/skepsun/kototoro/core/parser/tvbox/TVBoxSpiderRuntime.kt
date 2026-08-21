package org.skepsun.kototoro.core.parser.tvbox

import org.json.JSONObject
import org.skepsun.kototoro.core.model.jsonsource.TVBoxStoredConfig
import org.skepsun.kototoro.parsers.model.Content
import org.skepsun.kototoro.parsers.model.ContentChapter
import org.skepsun.kototoro.parsers.model.ContentListFilter
import org.skepsun.kototoro.parsers.model.ContentListFilterOptions
import org.skepsun.kototoro.parsers.model.ContentPage
import org.skepsun.kototoro.parsers.model.SortOrder

internal interface TVBoxSpiderRuntime {

    val id: String

    fun describeCapability(config: TVBoxStoredConfig): String

    fun describeUnavailability(config: TVBoxStoredConfig): String?

    suspend fun getList(
        offset: Int,
        order: SortOrder?,
        filter: ContentListFilter?,
    ): List<Content>?

    suspend fun getDetails(manga: Content, forceRefresh: Boolean = false): Content?

    suspend fun getPages(chapter: ContentChapter, nextChapterUrl: String?): List<ContentPage>?

    suspend fun getFilterOptions(): ContentListFilterOptions?

    suspend fun executeAction(action: String): TVBoxActionResult?

    fun getRequestHeaders(): Map<String, String>?
}

internal data class TVBoxActionResult(
    val message: String?,
) {
    companion object {
        fun parse(raw: String): TVBoxActionResult? {
            if (raw.isBlank()) return null
            val root = runCatching { JSONObject(raw) }.getOrNull()
            val message = if (root == null) {
                raw
            } else {
                sequenceOf("msg", "message", "error")
                    .map { root.optString(it).trim() }
                    .firstOrNull { it.isNotBlank() }
            }
            return TVBoxActionResult(message = message?.trim()?.takeIf { it.isNotBlank() })
        }
    }
}
