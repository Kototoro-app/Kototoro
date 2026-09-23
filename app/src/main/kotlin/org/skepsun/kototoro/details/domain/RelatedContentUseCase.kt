package org.skepsun.kototoro.details.domain

import org.skepsun.kototoro.core.model.ProjectionIdentityKeys
import org.skepsun.kototoro.core.parser.ContentRepository
import org.skepsun.kototoro.core.util.ext.printStackTraceDebug
import org.skepsun.kototoro.entitygraph.data.EntityGraphRepository
import org.skepsun.kototoro.entitygraph.domain.normalizeStrictTitleKey
import org.skepsun.kototoro.parsers.model.Content
import org.skepsun.kototoro.parsers.util.runCatchingCancellable
import java.net.URI
import javax.inject.Inject

class RelatedContentUseCase @Inject constructor(
    private val mangaRepositoryFactory: ContentRepository.Factory,
    private val entityGraphRepository: EntityGraphRepository,
) {

    suspend operator fun invoke(seed: Content) = runCatchingCancellable {
        getOrThrow(seed)
    }.onFailure {
        it.printStackTraceDebug()
    }.getOrNull()

    suspend fun getOrThrow(seed: Content): List<Content> {
        val boundProjectionKeys = findBoundProjectionKeys(seed)
        return filterCurrentWorkFromRelated(
            seed = seed,
            candidates = mangaRepositoryFactory.create(seed.source).getRelated(seed),
            boundProjectionKeys = boundProjectionKeys,
        )
    }

    private suspend fun findBoundProjectionKeys(seed: Content): Set<String> {
        if (seed.id == 0L) {
            return emptySet()
        }
        val entity = entityGraphRepository.findEntityByBinding("local_manga", seed.id.toString())
            ?: entityGraphRepository.findEntityByBinding("0", seed.id.toString())
            ?: return emptySet()
        return entityGraphRepository.getBindings(entity.id)
            .asSequence()
            .filter { binding -> binding.source == seed.source.name }
            .mapTo(LinkedHashSet()) { binding -> binding.externalId }
    }
}

internal fun filterCurrentWorkFromRelated(
    seed: Content,
    candidates: List<Content>,
    boundProjectionKeys: Set<String>,
): List<Content> {
    return candidates.filterNot { candidate ->
        if (candidate.source.name != seed.source.name) {
            return@filterNot false
        }
        candidate.id == seed.id ||
            ProjectionIdentityKeys.bindingKeys(candidate.url, candidate.publicUrl)
                .any(boundProjectionKeys::contains) ||
            ProjectionIdentityKeys.hasSameIdentity(
                source = seed.source.name,
                url = seed.url,
                publicUrl = seed.publicUrl,
                otherSource = candidate.source.name,
                otherUrl = candidate.url,
                otherPublicUrl = candidate.publicUrl,
            ) ||
            hasSameTitleAndCoverResource(seed, candidate)
    }
}

private fun hasSameTitleAndCoverResource(left: Content, right: Content): Boolean {
    val leftTitle = normalizeStrictTitleKey(left.title, listOf(left.source.name))
    val rightTitle = normalizeStrictTitleKey(right.title, listOf(right.source.name))
    if (leftTitle.isBlank() || leftTitle != rightTitle) {
        return false
    }
    val leftCoverKeys = sequenceOf(left.coverUrl, left.largeCoverUrl)
        .mapNotNull(::coverResourceKey)
        .toSet()
    return sequenceOf(right.coverUrl, right.largeCoverUrl)
        .mapNotNull(::coverResourceKey)
        .any(leftCoverKeys::contains)
}

private fun coverResourceKey(value: String?): String? {
    val raw = value?.trim()?.takeIf(String::isNotEmpty) ?: return null
    val uri = runCatching { URI(raw) }.getOrNull() ?: return raw
    if (!uri.scheme.equals("http", ignoreCase = true) && !uri.scheme.equals("https", ignoreCase = true)) {
        return raw
    }
    val path = uri.rawPath.orEmpty().ifBlank { return raw }
    return path
}
