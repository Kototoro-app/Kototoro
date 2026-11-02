package org.skepsun.kototoro.desktop.runtime

import org.skepsun.kototoro.core.source.SourceContent
import org.skepsun.kototoro.details.domain.RelatedWorkKey
import org.skepsun.kototoro.details.domain.RelatedWorkRules

/**
 * Android's `filterCurrentWorkFromRelated` as the details page needs it: a source's related list must never show the
 * work itself, nor a second copy of it that the source relists under another URL. The rule is shared with Android
 * ([RelatedWorkRules]); Windows adds the one thing a rail needs on top — the same work listed twice becomes one card.
 *
 * Candidates from another source are always kept: the seed's own id and URLs say nothing about a different site.
 */
fun desktopRelatedWorks(seed: SourceContent, candidates: List<SourceContent>): List<SourceContent> =
    RelatedWorkRules.filterCurrentWork(
        seed = seed.toRelatedWorkKey(),
        candidates = candidates,
        key = SourceContent::toRelatedWorkKey,
    ).distinctBy { candidate -> "${candidate.source.name}:${candidate.id}:${candidate.url}" }

private fun SourceContent.toRelatedWorkKey() = RelatedWorkKey(
    sourceName = source.name,
    id = id,
    url = url,
    publicUrl = publicUrl,
    title = title,
    coverUrl = coverUrl,
    largeCoverUrl = largeCoverUrl,
)
