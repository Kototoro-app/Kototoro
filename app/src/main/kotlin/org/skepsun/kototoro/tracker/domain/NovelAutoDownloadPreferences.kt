package org.skepsun.kototoro.tracker.domain

import android.content.Context
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.skepsun.kototoro.core.model.getContentType
import org.skepsun.kototoro.core.model.isLocal
import org.skepsun.kototoro.parsers.model.Content
import org.skepsun.kototoro.parsers.model.ContentType
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class NovelAutoDownloadPreferences @Inject constructor(@ApplicationContext context: Context) {
    private val preferences = context.getSharedPreferences("novel_auto_download", Context.MODE_PRIVATE)
    private val mutablePolicies = MutableStateFlow(
        preferences.all.entries.mapNotNull { (key, value) ->
            val id = key.toLongOrNull() ?: return@mapNotNull null
            val enabled = value as? Boolean ?: return@mapNotNull null
            id to enabled
        }.toMap(),
    )
    val policies = mutablePolicies.asStateFlow()

    fun enabledIds(): List<Long> = policies.value.filterValues { it }.keys.toList()

    @Synchronized
    fun toggle(mangaId: Long) {
        val enabled = policies.value[mangaId] != true
        preferences.edit { putBoolean(mangaId.toString(), enabled) }
        mutablePolicies.value += mangaId to enabled
    }
}

internal fun Content.canAutoDownloadNovelUpdates(): Boolean =
    !isLocal && source.getContentType() in setOf(ContentType.NOVEL, ContentType.HENTAI_NOVEL)
