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

private val GROUP_SOURCE_NAME = Regex("[A-Za-z0-9_.:-]{1,128}")
private val GROUP_DOMAIN_HOST = Regex("[A-Za-z0-9](?:[A-Za-z0-9.-]*[A-Za-z0-9])?(?::\\d{1,5})?")

/** True when [name] is a well-formed source name that is also one of the [knownNames] this device resolves. */
internal fun isValidGroupSourceName(name: String, knownNames: Set<String>): Boolean =
	GROUP_SOURCE_NAME.matches(name) && !name.contains("..") && name in knownNames

/** True for a bare host (optional port) with no scheme other than https, path, user info or whitespace. */
internal fun isValidGroupDomain(domain: String): Boolean {
	if (domain.length > 253 || domain.any { it.isWhitespace() }) return false
	val host = domain.removePrefix("https://")
	if (host.contains("://") || host.contains('/') || host.contains('@') || host.contains('\\')) return false
	return GROUP_DOMAIN_HOST.matches(host) && !host.startsWith("..") && !host.contains("..")
}
