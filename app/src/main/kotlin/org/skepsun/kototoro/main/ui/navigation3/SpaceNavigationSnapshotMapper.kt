package org.skepsun.kototoro.main.ui.navigation3

import org.skepsun.kototoro.space.domain.SpaceId
import org.skepsun.kototoro.space.domain.SpaceRouteSnapshot
import org.skepsun.kototoro.space.domain.SpaceSessionSnapshot

fun MainNavState.toSpaceSessionSnapshot(
    spaceId: SpaceId,
    timestamp: Long,
): SpaceSessionSnapshot {
    val stacks = stacksSnapshot().mapKeys { (key, _) -> encodeTopLevelNavKey(key) }
        .mapValues { (_, keys) -> keys.toSpaceRoutePrefix() }
    return SpaceSessionSnapshot(
        spaceId = spaceId,
        selectedTopLevel = encodeTopLevelNavKey(selectedTopLevel),
        resumeRoute = currentStack().lastOrNull()?.toSpaceRouteSnapshot(),
        stacks = stacks,
        lastAccessed = timestamp,
        updatedAt = timestamp,
    )
}

fun MainNavState.restoreFromSpaceSession(snapshot: SpaceSessionSnapshot) {
    allTopLevelNavKeys.forEach { topLevel ->
        val stackKey = encodeTopLevelNavKey(topLevel)
        val restored = snapshot.stacks[stackKey]
            .orEmpty()
            .toMainNavKeyPrefix()
            .takeWhile { key -> key !is TopLevelNavKey || key == topLevel }
            .ifEmpty { listOf(topLevel) }
        replaceStack(topLevel, restored.ensureRoot(topLevel))
    }
    navigateTopLevel(decodeTopLevelNavKey(snapshot.selectedTopLevel) ?: HomeNavKey)
}

private fun MainNavKey.toSpaceRouteSnapshot(): SpaceRouteSnapshot? = when (this) {
    is TopLevelNavKey -> SpaceRouteSnapshot.TopLevel(encodeTopLevelNavKey(this))
    is ContentListNavKey -> SpaceRouteSnapshot.ContentList(sourceName)
    is DetailsNavKey -> entityId?.let { SpaceRouteSnapshot.WorkDetails(it, requestedProjectionId) }
}

private fun SpaceRouteSnapshot.toMainNavKey(): MainNavKey? = when (this) {
    is SpaceRouteSnapshot.TopLevel -> decodeTopLevelNavKey(key)
    is SpaceRouteSnapshot.ContentList -> ContentListNavKey(sourceName)
    is SpaceRouteSnapshot.WorkDetails -> DetailsNavKey(entityId, requestedProjectionId)
}

private fun List<MainNavKey>.ensureRoot(root: TopLevelNavKey): List<MainNavKey> = when {
    firstOrNull() == root -> this
    else -> listOf(root) + filterNot { it is TopLevelNavKey }
}

private fun List<MainNavKey>.toSpaceRoutePrefix(): List<SpaceRouteSnapshot> {
    val result = ArrayList<SpaceRouteSnapshot>(size)
    for (key in this) {
        result += key.toSpaceRouteSnapshot() ?: break
    }
    return result
}

private fun List<SpaceRouteSnapshot>.toMainNavKeyPrefix(): List<MainNavKey> {
    val result = ArrayList<MainNavKey>(size)
    for (route in this) {
        result += route.toMainNavKey() ?: break
    }
    return result
}
