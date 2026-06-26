package org.skepsun.kototoro.explore.domain

import org.skepsun.kototoro.core.model.isLocal
import org.skepsun.kototoro.core.parser.ContentDataRepository
import org.skepsun.kototoro.core.parser.ContentRepository
import org.skepsun.kototoro.core.util.ext.printStackTraceDebug
import org.skepsun.kototoro.parsers.model.Content
import org.skepsun.kototoro.parsers.model.ContentListFilter
import org.skepsun.kototoro.parsers.util.runCatchingCancellable
import javax.inject.Inject

class RecoverContentUseCase @Inject constructor(
	private val mangaDataRepository: ContentDataRepository,
	private val repositoryFactory: ContentRepository.Factory,
) {

	suspend operator fun invoke(manga: Content): Content? = runCatchingCancellable {
		if (manga.isLocal) {
			return@runCatchingCancellable null
		}
		val repository = repositoryFactory.create(manga.source)
		val list = repository.getList(offset = 0, null, ContentListFilter(query = manga.title))
		val newContent = list.find { x -> x.title == manga.title }?.let {
			repository.getDetails(it)
		} ?: return@runCatchingCancellable null
		val merged = merge(manga, newContent)
		mangaDataRepository.storeContentAndReturn(merged, replaceExisting = true)
	}.onFailure {
		it.printStackTraceDebug()
	}.getOrNull()

	private fun merge(
		broken: Content,
		current: Content,
	) = Content(
		id = broken.id,
		title = current.title,
		altTitles = current.altTitles,
		url = current.url,
		publicUrl = current.publicUrl,
		rating = current.rating,
		contentRating = current.contentRating,
		coverUrl = current.coverUrl,
		tags = current.tags,
		state = current.state,
		authors = current.authors,
		largeCoverUrl = current.largeCoverUrl,
		description = current.description,
		chapters = current.chapters,
		source = current.source,
	)
}
