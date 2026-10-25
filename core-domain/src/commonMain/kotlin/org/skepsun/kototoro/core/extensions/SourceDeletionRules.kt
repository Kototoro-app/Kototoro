package org.skepsun.kototoro.core.extensions

/**
 * One selectable source as the delete step of the unified source page sees it: the name it is selected by, the
 * package that declares it, and whether that package may be removed from here.
 */
data class SourceDeletionCandidate(
    val sourceName: String,
    val packageId: String? = null,
    val packageName: String? = null,
    val uninstallable: Boolean = true,
)

/**
 * Android's `UnifiedSelectedSourceDeletePlan`: the packages a selection would remove, plus the names that were left
 * alone because deleting them would have taken a whole single-file JAR plugin with them.
 *
 * [packageIds] and [packageNames] are parallel and keep the order the candidates were given in, so the dialog can
 * count the packages and the caller can uninstall them without re-deriving anything.
 */
data class SelectedSourcesDeletePlan(
    val packageIds: List<String> = emptyList(),
    val packageNames: List<String> = emptyList(),
    val skippedNames: List<String> = emptyList(),
) {

    val hasPackages: Boolean get() = packageIds.isNotEmpty()

    /** Nothing selected maps to anything removable: the dialog only says so. */
    val isEmpty: Boolean get() = packageIds.isEmpty() && skippedNames.isEmpty()
}

/**
 * Folds a selection of source names into the packages to uninstall, one entry per package however many of its sources
 * were picked. A source without a package (an inline JSON source) contributes nothing, exactly like Android's plan;
 * a source declared by a package that cannot be removed from the page is reported through [SelectedSourcesDeletePlan
 * .skippedNames] instead of being deleted by accident.
 */
fun selectedSourcesDeletePlan(
    selectedSourceNames: Set<String>,
    candidates: List<SourceDeletionCandidate>,
): SelectedSourcesDeletePlan {
    if (selectedSourceNames.isEmpty()) return SelectedSourcesDeletePlan()
    val packageIds = LinkedHashSet<String>()
    val packageNames = ArrayList<String>()
    val skipped = LinkedHashSet<String>()
    for (candidate in candidates) {
        if (candidate.sourceName !in selectedSourceNames) continue
        val name = candidate.packageName?.takeIf { it.isNotBlank() } ?: candidate.sourceName
        if (!candidate.uninstallable) {
            skipped += name
            continue
        }
        val packageId = candidate.packageId ?: continue
        if (packageIds.add(packageId)) packageNames += name
    }
    return SelectedSourcesDeletePlan(packageIds.toList(), packageNames, skipped.toList())
}
