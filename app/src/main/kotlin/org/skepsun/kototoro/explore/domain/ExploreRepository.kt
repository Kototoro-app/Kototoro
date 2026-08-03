package org.skepsun.kototoro.explore.domain

import org.skepsun.kototoro.core.model.isNsfw
import org.skepsun.kototoro.core.model.GlobalTagBlacklist
import org.skepsun.kototoro.core.parser.ContentRepository
import org.skepsun.kototoro.core.prefs.AppSettings
import org.skepsun.kototoro.core.util.ext.asArrayList
import org.skepsun.kototoro.core.util.ext.printStackTraceDebug
import org.skepsun.kototoro.explore.data.ContentSourcesRepository
import org.skepsun.kototoro.history.data.HistoryRepository
import org.skepsun.kototoro.parsers.model.Content
import org.skepsun.kototoro.parsers.model.ContentListFilter
import org.skepsun.kototoro.parsers.model.ContentSource
import org.skepsun.kototoro.parsers.util.almostEquals
import org.skepsun.kototoro.parsers.util.runCatchingCancellable
import org.skepsun.kototoro.suggestions.domain.TagsBlacklist
import javax.inject.Inject

class ExploreRepository @Inject constructor(
	private val settings: AppSettings,
	private val sourcesRepository: ContentSourcesRepository,
	private val historyRepository: HistoryRepository,
	private val mangaRepositoryFactory: ContentRepository.Factory,
) {

	suspend fun findRandomContent(tagsLimit: Int): Content {
		val tagsBlacklist = TagsBlacklist(settings.suggestionsTagsBlacklist, 0.4f)
		val globalTagBlacklist = GlobalTagBlacklist(settings.globalTagBlacklist)
		val tagsWhitelist = settings.suggestionsTagsWhitelist.toList()
		val tags = (tagsWhitelist + historyRepository.getPopularTags(tagsLimit).mapNotNull {
			if (it in tagsBlacklist) null else it.title
		}).distinct()
		val sources = sourcesRepository.getEnabledSources()
		check(sources.isNotEmpty()) { "No sources available" }
		for (i in 0..4) {
			val list = getList(sources.random(), tags, tagsBlacklist)
			val manga = list.randomOrNull() ?: continue
			val details = runCatchingCancellable {
				mangaRepositoryFactory.create(manga.source).getDetails(manga)
			}.getOrNull() ?: continue
			if (
				(settings.isSuggestionsExcludeNsfw && details.isNsfw()) ||
				details in tagsBlacklist ||
				details in globalTagBlacklist
			) {
				continue
			}
			return details
		}
		throw NoSuchElementException()
	}

	suspend fun findRandomContent(source: ContentSource, tagsLimit: Int): Content {
		val tagsBlacklist = TagsBlacklist(settings.suggestionsTagsBlacklist, 0.4f)
		val globalTagBlacklist = GlobalTagBlacklist(settings.globalTagBlacklist)
		val skipNsfw = settings.isSuggestionsExcludeNsfw && !source.isNsfw()
		val tagsWhitelist = settings.suggestionsTagsWhitelist.toList()
		val tags = (tagsWhitelist + historyRepository.getPopularTags(tagsLimit).mapNotNull {
			if (it in tagsBlacklist) null else it.title
		}).distinct()
		for (i in 0..4) {
			val list = getList(source, tags, tagsBlacklist)
			val manga = list.randomOrNull() ?: continue
			val details = runCatchingCancellable {
				mangaRepositoryFactory.create(manga.source).getDetails(manga)
			}.getOrNull() ?: continue
			if ((skipNsfw && details.isNsfw()) || details in tagsBlacklist || details in globalTagBlacklist) {
				continue
			}
			return details
		}
		throw NoSuchElementException()
	}

	private suspend fun getList(
		source: ContentSource,
		tags: List<String>,
		blacklist: TagsBlacklist,
	): List<Content> = runCatchingCancellable {
		val repository = mangaRepositoryFactory.create(source)
		val order = repository.sortOrders.random()
		val availableTags = repository.getFilterOptions().availableTags
		val tag = tags.firstNotNullOfOrNull { title ->
			availableTags.find { x -> x.title.almostEquals(title, 0.4f) }
		}
		val list = repository.getList(
			offset = 0,
			order = order,
			filter = ContentListFilter(tags = setOfNotNull(tag)),
		).asArrayList()
		if (settings.isSuggestionsExcludeNsfw) {
			list.removeAll { it.isNsfw() }
		}
		if (blacklist.isNotEmpty()) {
			list.removeAll { manga -> manga in blacklist }
		}
		val globalTagBlacklist = GlobalTagBlacklist(settings.globalTagBlacklist)
		if (!globalTagBlacklist.isEmpty) {
			list.removeAll { manga -> manga in globalTagBlacklist }
		}
		list.shuffle()
		list
	}.onFailure {
		it.printStackTraceDebug()
	}.getOrDefault(emptyList())
}
