package org.skepsun.kototoro.suggestions.domain

import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.first
import org.skepsun.kototoro.core.prefs.AppSettings
import org.skepsun.kototoro.core.model.getLocale
import org.skepsun.kototoro.core.model.isNsfw
import org.skepsun.kototoro.core.model.GlobalTagBlacklist
import org.skepsun.kototoro.core.db.MangaDatabase
import org.skepsun.kototoro.core.db.entity.toEntities
import org.skepsun.kototoro.core.db.entity.toEntity
import org.skepsun.kototoro.core.db.entity.toContent
import org.skepsun.kototoro.core.db.entity.toContentTagsList
import org.skepsun.kototoro.core.model.toContentSources
import org.skepsun.kototoro.list.domain.ListFilterOption
import org.skepsun.kototoro.list.domain.toCriteria
import org.skepsun.kototoro.parsers.model.Content
import org.skepsun.kototoro.parsers.model.ContentSource
import org.skepsun.kototoro.parsers.model.ContentTag
import org.skepsun.kototoro.suggestions.data.SuggestionEntity
import org.skepsun.kototoro.suggestions.data.SuggestionWithContent
import javax.inject.Inject

class SuggestionRepository @Inject constructor(
    private val db: MangaDatabase,
    private val settings: AppSettings,
) {

    fun observeAll(): Flow<List<Content>> {
        return db.getSuggestionDao().observeAll().withCurrentPreferences()
    }

    fun observeAll(
        limit: Int,
        filterOptions: Set<ListFilterOption>,
        contentTypes: Collection<String>? = null,
        direction: SuggestionDirection? = null,
    ): Flow<List<Content>> {
        return db.getSuggestionDao().observeAll(Int.MAX_VALUE, filterOptions.toCriteria(), contentTypes, direction?.name)
            .withCurrentPreferences().map { it.take(limit) }
    }

    fun observeCount(): Flow<Int> {
        return observeAll().map { it.size }
    }

    fun observeReasons(): Flow<Map<Long, Pair<SuggestionDirection, String?>>> = db.getSuggestionDao().observeAll().map { rows ->
        rows.associate { it.suggestion.mangaId to (SuggestionDirection.of(it.suggestion.direction) to it.suggestion.reason) }
    }

    suspend fun getRandomList(limit: Int): List<Content> {
        return if (settings.isCommunityEnabled) {
            filter(db.getSuggestionDao().getRandom(limit).map { it.toContent() })
        } else {
            observeAll().first().shuffled().take(limit)
        }
    }

    suspend fun count(): Int = db.getSuggestionDao().count()

    suspend fun knownIds(): Set<Long> = db.getSuggestionDao().findAll().mapTo(HashSet()) { it.mangaId }

    /** Adds [suggestions] below everything already stored, keeping their own order; works already stored are skipped. */
    suspend fun append(suggestions: Iterable<ContentSuggestion>): Int = db.withTransaction {
        val existing = db.getSuggestionDao().findAll()
        val stored = existing.mapTo(HashSet()) { it.mangaId }
        val floor = existing.minOfOrNull { it.relevance } ?: 1f
        var added = 0
        suggestions.filter { it.manga.id !in stored }.forEach { suggestion ->
            val manga = suggestion.manga
            val tags = manga.tags.toEntities()
            db.getTagsDao().upsert(tags)
            db.getMangaDao().upsert(manga.toEntity(), tags)
            db.getSuggestionDao().upsert(
                SuggestionEntity(
                    mangaId = manga.id,
                    relevance = floor * 0.98f * suggestion.relevance,
                    createdAt = System.currentTimeMillis(),
                    direction = suggestion.direction.name,
                    reason = suggestion.reason,
                ),
            )
            added++
        }
        added
    }

    suspend fun clear() {
        db.getSuggestionDao().deleteAll()
    }

    suspend fun isEmpty(): Boolean {
        return observeAll().first().isEmpty()
    }

    suspend fun getTopTags(limit: Int, accepts: (ContentSource) -> Boolean = { true }): List<ContentTag> {
        return observeAll().first().filter { accepts(it.source) }.flatMap { it.tags }.mostFrequent(limit)
    }

    suspend fun getTopSources(limit: Int, accepts: (ContentSource) -> Boolean = { true }): List<ContentSource> {
        return observeAll().first().map { it.source }.filter(accepts).mostFrequent(limit)
    }

    /**
     * The library as evidence of taste: history most recently read first, then what was saved and never opened.
     * Same rows as the shared `SourceLibrary.libraryWorks`.
     */
    suspend fun getLibraryWorks(historyLimit: Int): List<LibraryWork> {
        val read = db.getHistoryDao().findRecent(historyLimit)
        val saved = db.getFavouritesDao().findAllWithActiveCategory(0, Int.MAX_VALUE).distinctBy { it.manga.id }
        val savedIds = saved.mapTo(HashSet(saved.size)) { it.manga.id }
        val readIds = read.mapTo(HashSet(read.size)) { it.manga.id }
        return read.map { row ->
            LibraryWork(
                titles = listOf(row.manga.title) + row.manga.altTitles?.split('\n').orEmpty(),
                tags = row.tags.map { it.title },
                isFavourite = row.manga.id in savedIds,
                percent = row.history.percent.takeIf { it >= 0f },
                chapters = row.history.chaptersCount,
                lastReadAt = row.history.updatedAt,
                id = row.manga.id,
                source = row.manga.source,
                contentType = row.manga.contentType,
                isAdult = row.manga.isNsfw,
            )
        } + saved.filterNot { it.manga.id in readIds }.map { row ->
            LibraryWork(
                titles = listOf(row.manga.title) + row.manga.altTitles?.split('\n').orEmpty(),
                tags = row.tags.map { it.title },
                isFavourite = true,
                id = row.manga.id,
                source = row.manga.source,
                contentType = row.manga.contentType,
                isAdult = row.manga.isNsfw,
            )
        }
    }

    /** A suggestion already on the list keeps the time it was first put there, so one nobody opens can sink. */
    suspend fun replace(suggestions: Iterable<ContentSuggestion>) {
        db.withTransaction {
            val ages = db.getSuggestionDao().findAll().associate { it.mangaId to it.createdAt }
            db.getSuggestionDao().deleteAll()
            suggestions.forEach { suggestion ->
                val manga = suggestion.manga
                val tags = manga.tags.toEntities()
                db.getTagsDao().upsert(tags)
                db.getMangaDao().upsert(manga.toEntity(), tags)
                db.getSuggestionDao().upsert(
                    SuggestionEntity(
                        mangaId = manga.id,
                        relevance = suggestion.relevance,
                        createdAt = ages[manga.id] ?: System.currentTimeMillis(),
                        direction = suggestion.direction.name,
                        reason = suggestion.reason,
                    ),
                )
            }
        }
    }

    private fun Flow<List<SuggestionWithContent>>.withCurrentPreferences(): Flow<List<Content>> = combine(
        this,
        settings.observeChanges().onStart { emit("") },
    ) { items, _ -> visibleContent(items) }

    private fun visibleContent(rows: List<SuggestionWithContent>): List<Content> = filter(rows.asSequence()
        .filter { settings.isCommunityEnabled || it.suggestion.direction != SuggestionDirection.COMMUNITY.name }
        .map { it.toContent() }.toList())

    private fun filter(items: List<Content>): List<Content> {
        val excluded = SuggestionTagBlacklist(settings.suggestionsTagsBlacklist, ::suggestionTagsMatch)
        val global = GlobalTagBlacklist(settings.globalTagBlacklist)
        return items.filter { item ->
            (!settings.suggestionsLanguageOnly || settings.suggestionsLanguage.accepts(
                item.source.getLocale()?.toLanguageTag(), java.util.Locale.getDefault().language,
            )) &&
                item.source.name !in settings.suggestionsExcludedSources &&
                (!(settings.isSuggestionsExcludeNsfw || settings.isNsfwContentDisabled) || !item.isNsfw()) &&
                !excluded.containsAny(item.tags.map { it.title }) && item !in global
        }.sortedByDescending {
            settings.suggestionsLanguage.accepts(it.source.getLocale()?.toLanguageTag(), java.util.Locale.getDefault().language)
        }
    }

    private fun SuggestionWithContent.toContent() = manga.toContent(tags.toContentTagsList().toSet(), null)
}
