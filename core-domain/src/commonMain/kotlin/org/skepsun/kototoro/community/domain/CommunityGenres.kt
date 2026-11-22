package org.skepsun.kototoro.community.domain

import org.skepsun.kototoro.core.model.TagTaxonomy
import org.skepsun.kototoro.core.model.TaxonomyCategory

/**
 * What a work is about, in a vocabulary every source and language shares: its source tags mapped onto the Kototoro
 * taxonomy's permanent ids. Only those ids are published with a rating, never a source's own labels, so nothing a
 * parser invents reaches the relays. Content warnings, raw source tags and the adult markers are left out: they say
 * little about what a work is like, and the adult flag travels separately.
 */
object CommunityGenres {

    /** At most this many per rating; enough to tell two works apart, small enough to keep events short. */
    const val MAX = 16

    private val SKIPPED = setOf(TaxonomyCategory.RAW, TaxonomyCategory.WARNING)

    fun of(tagTitles: Iterable<String>): List<String> = tagTitles.asSequence()
        .flatMap { TagTaxonomy.resolve(it).asSequence() }
        .filter { it.category !in SKIPPED && !it.adult }
        .map { it.id }
        .distinct()
        .sorted()
        .take(MAX)
        .toList()

    /** Whether [id] is one this vocabulary knows; the index ignores anything else an event claims. */
    fun isKnown(id: String): Boolean = TagTaxonomy.find(id)?.let { it.category !in SKIPPED && !it.adult } == true

    fun sharedCount(a: Collection<String>, b: Collection<String>): Int = a.count { it in b }

    /** Jaccard overlap of two tag lists; nothing in common when either has none. */
    fun overlap(a: Collection<String>, b: Collection<String>): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        val shared = a.count { it in b }
        return shared.toDouble() / (a.size + b.size - shared)
    }
}
