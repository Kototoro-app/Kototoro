package org.skepsun.kototoro.suggestions.domain

import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.produceIn
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.core.db.MangaDatabase
import org.skepsun.kototoro.core.db.entity.MangaEntity
import org.skepsun.kototoro.core.db.entity.TagEntity
import org.skepsun.kototoro.core.prefs.AppSettings
import org.skepsun.kototoro.suggestions.data.SuggestionDao
import org.skepsun.kototoro.suggestions.data.SuggestionEntity
import org.skepsun.kototoro.suggestions.data.SuggestionWithContent

class SuggestionRepositoryVisibilityTest {
    @Test
    fun `cached recommendations retain tags and react to preference changes without a refresh`() = runBlocking {
        val changes = MutableSharedFlow<String>()
        var excluded = emptySet<String>()
        var global = emptySet<String>()
        val settings = mockk<AppSettings> {
            every { observeChanges() } returns changes
            every { suggestionsTagsBlacklist } answers { excluded }
            every { globalTagBlacklist } answers { global }
            every { suggestionsLanguage } returns SuggestionLanguage.ALL
            every { suggestionsLanguageOnly } returns false
            every { suggestionsExcludedSources } returns emptySet()
            every { isSuggestionsExcludeNsfw } returns false
            every { isNsfwContentDisabled } returns false
        }
        val row = SuggestionWithContent(
            SuggestionEntity(1, 1f, 1),
            MangaEntity(id = 1, title = "Cached work", altTitles = null, url = "/1", publicUrl = "https://fixture.invalid/1",
                rating = -1f, isNsfw = false, contentRating = "SAFE", coverUrl = "", largeCoverUrl = null,
                state = null, authors = null, source = "fixture", contentType = "MANGA"),
            listOf(TagEntity(1, "恋爱", "romance", "fixture", false)),
        )
        val dao = mockk<SuggestionDao> { every { observeAll() } returns flowOf(listOf(row)) }
        val database = mockk<MangaDatabase> { every { getSuggestionDao() } returns dao }
        val channel = SuggestionRepository(database, settings).observeAll().produceIn(this)
        try {
            withTimeout(5000) {
                assertEquals(setOf("恋爱"), channel.receive().single().tags.map { it.title }.toSet())
                changes.subscriptionCount.first { it > 0 }
                excluded = setOf("Romance")
                changes.emit(AppSettings.KEY_SUGGESTIONS_EXCLUDE_TAGS)
                assertTrue(channel.receive().isEmpty())
                excluded = emptySet()
                global = setOf("genre.romance")
                changes.emit(AppSettings.KEY_GLOBAL_TAG_BLACKLIST)
                assertTrue(channel.receive().isEmpty())
                global = emptySet()
                changes.emit(AppSettings.KEY_GLOBAL_TAG_BLACKLIST)
                assertEquals(1, channel.receive().size)
                val other = row.copy(suggestion = SuggestionEntity(2, .5f, 1), manga = row.manga.copy(id = 2),
                    tags = listOf(TagEntity(2, "恐怖", "horror", "fixture", false)))
                every { dao.observeAll(any(), any(), any(), any()) } answers {
                    flowOf(listOf(row, other).take(firstArg()))
                }
                excluded = setOf("Romance")
                assertEquals(2L, SuggestionRepository(database, settings).observeAll(1, emptySet()).first().single().id,
                    "apply visibility before the home rail limit so allowed cached rows are not lost")
            }
        } finally { channel.cancel() }
    }
}
