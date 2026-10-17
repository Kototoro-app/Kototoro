package org.skepsun.kototoro.migration.domain

data class RecoveryReference(val sourceKey: String, val workKey: String, val favourite: Boolean)
data class RecoveryPackage(val key: String, val sourceKeys: List<String>)
data class MissingLibrarySource(val sourceKey: String, val works: Int, val favourites: Int, val history: Int)

/** Strict wire identities keep equal numeric IDs in different ecosystems independent. */
object SourceRecoveryRules {
    fun missing(references: List<RecoveryReference>, installed: Set<String>): List<MissingLibrarySource> =
        references.filter {
            it.sourceKey !in installed && it.sourceKey !in setOf("LOCAL", "LOCAL_NOVEL", "LOCAL_VIDEO")
        }
            .groupBy { it.sourceKey }.map { (key, entries) ->
                MissingLibrarySource(key, entries.map { it.workKey }.distinct().size,
                    entries.filter { it.favourite }.map { it.workKey }.distinct().size,
                    entries.filterNot { it.favourite }.map { it.workKey }.distinct().size)
            }.sortedWith(compareByDescending<MissingLibrarySource> { it.works }.thenBy { it.sourceKey })

    /** Greedy coverage avoids installing several alternative packages for the same missing source. */
    fun recommended(missing: List<MissingLibrarySource>, packages: List<RecoveryPackage>): List<String> {
        val remaining = missing.mapTo(mutableSetOf()) { it.sourceKey }
        val available = packages.distinctBy { it.key }.toMutableList()
        val result = mutableListOf<String>()
        while (remaining.isNotEmpty()) {
            val best = available.sortedWith(compareByDescending<RecoveryPackage> {
                it.sourceKeys.toSet().count { source -> source in remaining }
            }.thenBy { it.key }).firstOrNull() ?: break
            if (best.sourceKeys.none { it in remaining }) break
            result += best.key
            remaining.removeAll(best.sourceKeys.toSet())
            available.remove(best)
        }
        return result
    }
}
