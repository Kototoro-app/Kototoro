package org.skepsun.kototoro.core.extensions

const val DEFAULT_JAR_PRIORITY_ORDER_VALUE =
    "kototoro-parsers,kotatsu-parsers-redo,uma,kotatsu-parsers"

/**
 * Trailing version marker of a JAR file name, e.g. `parsers.v703`, `parsers-1.9.9`, `parsers_134`.
 * A separator is required, so `parsers.extra` is not mistaken for a versioned build of `parsers`.
 */
private val JAR_VERSION_SUFFIX = Regex("""^(.*?)[._-]v?(\d+(?:[._]\d+)*)$""")

fun resolveJarPriorityOrder(
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

fun jarPriorityComparator(priorityOrder: String): Comparator<String> {
    val priorities = jarPriorityRanks(priorityOrder)
    return compareBy<String> { priorities[it.jarBaseName().lowercase()] ?: Int.MAX_VALUE }
        .thenBy { it.jarBaseName().lowercase() }
}

/**
 * Ranks a JAR for a source that several JARs provide. The configured order decides first; JARs that
 * are not listed there are ordered by the version in their file name, and only then alphabetically.
 * Version comparison is limited to JARs of the same package, so two unrelated JARs never get ordered
 * by a number that happens to appear in their name.
 */
fun <T> jarCandidateComparator(
    priorityOrder: String,
    jarName: (T) -> String,
): Comparator<T> {
    val priorities = jarPriorityRanks(priorityOrder)
    return compareBy<T> { priorities[jarName(it).jarBaseName().lowercase()] ?: Int.MAX_VALUE }
        .thenComparator { a, b ->
            val nameA = jarName(a)
            val nameB = jarName(b)
            if (nameA.jarPackageKey() == nameB.jarPackageKey()) {
                // Newer build of the same package wins.
                nameB.jarVersionCode().compareTo(nameA.jarVersionCode())
            } else {
                0
            }
        }
        .thenBy { jarName(it).jarBaseName().lowercase() }
}

fun <T> selectPreferredJarSources(
    sources: List<T>,
    priorityOrder: String,
    sourceName: (T) -> String,
    jarName: (T) -> String,
): List<T> {
    val comparator = jarCandidateComparator(priorityOrder, jarName)
    return sources
        .groupBy(sourceName)
        .values
        .map { candidates -> candidates.minWith(comparator) }
}

/**
 * Returns, for every JAR whose source lost deduplication, a map of `sourceName -> winningJarName`.
 * Keys are the losing JAR names. Sources that win are not included.
 */
fun <T> findShadowedJarSources(
    sources: List<T>,
    priorityOrder: String,
    sourceName: (T) -> String,
    jarName: (T) -> String,
): Map<String, Map<String, String>> {
    val comparator = jarCandidateComparator(priorityOrder, jarName)
    val result = mutableMapOf<String, MutableMap<String, String>>()
    sources.groupBy(sourceName).forEach { (name, candidates) ->
        val winnerJar = jarName(candidates.minWith(comparator))
        candidates.forEach { candidate ->
            val candidateJar = jarName(candidate)
            if (candidateJar != winnerJar) {
                result.getOrPut(candidateJar) { mutableMapOf() }[name] = winnerJar
            }
        }
    }
    return result
}

/**
 * Keeps one JAR per package: the highest version, then the alphabetically first name.
 * Two JARs of the same package hold the same classes and the same sources, so loading both only
 * produces duplicate plugins where every source of one of them looks shadowed.
 */
fun <T> deduplicateJarPackages(
    plugins: List<T>,
    jarName: (T) -> String,
): JarDeduplicationResult<T> {
    val winners = plugins
        .groupBy { jarName(it).jarPackageKey() }
        .values
        .mapTo(mutableSetOf()) { group ->
            group.maxWith(
                compareBy<T> { jarName(it).jarVersionCode() }
                    .thenByDescending { jarName(it).jarBaseName().lowercase() },
            )
        }
    return JarDeduplicationResult(
        kept = plugins.filter { it in winners },
        dropped = plugins.filterNot { it in winners },
    )
}

data class JarDeduplicationResult<T>(
    val kept: List<T>,
    val dropped: List<T>,
)

/**
 * Package identity of a JAR file name: the name without extension and without its trailing version
 * marker, so `org.skepsun.kototoro.parsers.jar` and `org.skepsun.kototoro.parsers.v703.jar` share a
 * key while `org.skepsun.kototoro.parsers.extra.jar` keeps its own.
 */
fun String.jarPackageKey(): String = jarIdentity(this).key

/**
 * Version encoded from the trailing version marker of a JAR file name, `0` when the name has none.
 */
fun String.jarVersionCode(): Long = jarIdentity(this).version

fun String.jarBaseName(): String =
    if (endsWith(".jar", ignoreCase = true)) dropLast(4) else this

private fun jarPriorityRanks(priorityOrder: String): Map<String, Int> {
    return priorityOrder
        .split(',')
        .map { it.trim().lowercase() }
        .filter { it.isNotEmpty() }
        .distinct()
        .withIndex()
        .associate { (index, name) -> name to index }
}

private fun jarIdentity(fileName: String): JarPackageIdentity {
    val baseName = fileName.jarBaseName()
    val match = JAR_VERSION_SUFFIX.matchEntire(baseName) ?: return JarPackageIdentity(baseName, 0L)
    val packageKey = match.groupValues[1]
    if (packageKey.isEmpty() || packageKey.all { it == '.' || it == '-' || it == '_' }) {
        return JarPackageIdentity(baseName, 0L)
    }
    return JarPackageIdentity(packageKey, encodeJarVersion(match.groupValues[2]))
}

private fun encodeJarVersion(rawVersion: String): Long {
    var code = 0L
    rawVersion.split('.', '_').take(3).forEach { part ->
        code = code * 1000L + (part.toLongOrNull()?.coerceIn(0L, 999L) ?: 0L)
    }
    return code
}

private data class JarPackageIdentity(
    val key: String,
    val version: Long,
)
