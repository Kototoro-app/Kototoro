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
        mangaRepositoryFactory.create(seed.source).getRelated(seed)
    }.onFailure {
        it.printStackTraceDebug()
    }.getOrNull()
}
