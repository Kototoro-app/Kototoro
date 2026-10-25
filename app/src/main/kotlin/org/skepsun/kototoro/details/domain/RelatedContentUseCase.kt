package org.skepsun.kototoro.details.domain

import org.skepsun.kototoro.core.parser.ContentRepository
import org.skepsun.kototoro.core.util.ext.printStackTraceDebug
import org.skepsun.kototoro.parsers.model.Content
import org.skepsun.kototoro.parsers.util.runCatchingCancellable
import javax.inject.Inject

class RelatedContentUseCase @Inject constructor(
    private val mangaRepositoryFactory: ContentRepository.Factory,
) {

    suspend operator fun invoke(seed: Content) = runCatchingCancellable {
        getOrThrow(seed)
    }.onFailure {
        it.printStackTraceDebug()
    }.getOrNull()

    suspend fun getOrThrow(seed: Content): List<Content> {
        return filterCurrentWorkFromRelated(
            seed = seed,
            candidates = mangaRepositoryFactory.create(seed.source).getRelated(seed),
        )
    }
}

/**
 * The work itself out of its source's related list. The rule is shared with Windows
 * ([org.skepsun.kototoro.details.domain.RelatedWorkRules]); only the projection onto its fields is Android's.
 */
internal fun filterCurrentWorkFromRelated(
    seed: Content,
    candidates: List<Content>,
): List<Content> = RelatedWorkRules.filterCurrentWork(
    seed = seed.toRelatedWorkKey(),
    candidates = candidates,
    key = Content::toRelatedWorkKey,
)

private fun Content.toRelatedWorkKey() = RelatedWorkKey(
    sourceName = source.name,
    id = id,
    url = url,
    publicUrl = publicUrl,
    title = title,
    coverUrl = coverUrl,
    largeCoverUrl = largeCoverUrl,
)
