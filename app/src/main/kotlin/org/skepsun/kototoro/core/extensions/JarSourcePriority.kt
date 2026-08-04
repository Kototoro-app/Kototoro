package org.skepsun.kototoro.core.extensions

internal const val DEFAULT_JAR_PRIORITY_ORDER_VALUE =
    "kototoro-parsers,kotatsu-parsers-redo,uma,kotatsu-parsers"

internal fun resolveJarPriorityOrder(
    installedJarNames: List<String>,
    savedOrder: String,
): List<String> {
    val installed = installedJarNames.distinctBy { it.jarBaseName().lowercase() }
    val installedByBaseName = installed.associateBy { it.jarBaseName().lowercase() }
    val ordered = savedOrder
        .split(',')
        .map { it.trim().lowercase() }
        .mapNotNull(installedByBaseName::get)
        .distinctBy { it.jarBaseName().lowercase() }
        .toMutableList()
    installed
        .filterNot { candidate -> ordered.any { it.jarBaseName().equals(candidate.jarBaseName(), ignoreCase = true) } }
        .sortedBy { it.jarBaseName().lowercase() }
        .forEach(ordered::add)
    return ordered
}

internal fun jarPriorityComparator(priorityOrder: String): Comparator<String> {
    val priorities = priorityOrder
        .split(',')
        .map { it.trim().lowercase() }
        .filter { it.isNotEmpty() }
        .distinct()
        .withIndex()
        .associate { (index, name) -> name to index }
    return compareBy<String> { priorities[it.jarBaseName().lowercase()] ?: Int.MAX_VALUE }
        .thenBy { it.jarBaseName().lowercase() }
}

internal fun <T> selectPreferredJarSources(
    sources: List<T>,
    priorityOrder: String,
    sourceName: (T) -> String,
    jarName: (T) -> String,
): List<T> {
    val comparator = compareBy(jarPriorityComparator(priorityOrder), jarName)
    return sources
        .groupBy(sourceName)
        .values
        .map { candidates -> candidates.minWith(comparator) }
}

internal fun String.jarBaseName(): String =
    if (endsWith(".jar", ignoreCase = true)) dropLast(4) else this
