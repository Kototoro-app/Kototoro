package org.skepsun.kototoro.settings.sources.groups

/**
 * Lock changes of a group apply, by source name (null = clear the lock). Locks an earlier group apply wrote
 * for sources this group does not list are released; a null lock only clears a lock that an earlier group
 * apply wrote, never one the user set by hand.
 */
internal fun groupLockPlan(
	groupLocks: Map<String, Boolean?>,
	previouslyApplied: Set<String>,
): Map<String, Boolean?> {
	val released: Map<String, Boolean?> = previouslyApplied.filterNot { it in groupLocks.keys }.associateWith { null }
	val effective = groupLocks.filter { (name, lock) -> lock != null || name in previouslyApplied }
	return released + effective
}

/** The JAR priority string to store, or null when the user's own order must stay untouched. */
internal fun groupJarPriorityToApply(groupJarPriority: List<String>, installedJarNames: Set<String>): String? {
	val filtered = groupJarPriority.filter { it.trim().lowercase() in installedJarNames }
	return filtered.takeIf { it.isNotEmpty() }?.joinToString(",")
}
