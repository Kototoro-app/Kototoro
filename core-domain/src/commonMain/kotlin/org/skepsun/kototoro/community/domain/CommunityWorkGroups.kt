package org.skepsun.kototoro.community.domain

/**
 * Which published keys are one work, worked out from the keys events carry together (plan §3.4). The indexer builds
 * this from every archived event; it is deterministic, so rebuilding from the same events gives the same groups.
 *
 * - Strong keys on one event are joined, the pairs most events agree on first. A join that would give a work two ids
 *   on one site is refused and counted in [conflicts]: the majority wins and a wrong binding stays on its own.
 * - A title key joins the strong group it is seen with most often. When two groups tie it joins neither and is counted
 *   in [ambiguousTitleKeys], so a common title cannot pull ratings into the wrong work.
 * - Title keys of a work nobody bound to a site join each other, and a title join never merges two strong groups.
 */
class CommunityWorkGroups private constructor(
    private val groupIds: Map<String, String>,
    val conflicts: Int,
    val ambiguousTitleKeys: Int,
) {

    /** The group a key belongs to; null for a key no event carried. */
    fun groupOf(key: String): String? = groupIds[key]

    /** Each group's id and its keys, sorted. */
    val groups: Map<String, List<String>> by lazy {
        groupIds.entries.groupBy({ it.value }, { it.key }).mapValues { (_, keys) -> keys.sorted() }
    }

    companion object {

        fun build(events: Iterable<Collection<String>>): CommunityWorkGroups {
            val keySets = events.map { it.distinct().sorted() }.filter { it.isNotEmpty() }.sortedBy { it.joinToString("\n") }
            val sets = KeySets()
            keySets.forEach { keys -> keys.forEach(sets::add) }

            val pairSupport = HashMap<Pair<String, String>, Int>()
            for (keys in keySets) {
                val strong = keys.filter(CommunityWorkKeys::isStrong)
                for (i in strong.indices) {
                    for (j in i + 1 until strong.size) {
                        val pair = strong[i] to strong[j]
                        pairSupport[pair] = (pairSupport[pair] ?: 0) + 1
                    }
                }
            }
            var conflicts = 0
            pairSupport.entries
                .sortedWith(compareByDescending<Map.Entry<Pair<String, String>, Int>> { it.value }
                    .thenBy { it.key.first }.thenBy { it.key.second })
                .forEach { (pair, _) -> if (!sets.union(pair.first, pair.second, allowTwoStrong = true)) conflicts++ }

            // Counted against the strong groups as they stand now, before any title key moves them.
            val titleSupport = HashMap<String, HashMap<String, Int>>()
            val weakOnly = ArrayList<List<String>>()
            for (keys in keySets) {
                val (strong, weak) = keys.partition(CommunityWorkKeys::isStrong)
                if (strong.isEmpty()) {
                    weakOnly += weak
                    continue
                }
                val roots = strong.mapTo(HashSet(), sets::find)
                if (roots.size != 1) continue
                val root = roots.single()
                for (key in weak) {
                    val counts = titleSupport.getOrPut(key, ::HashMap)
                    counts[root] = (counts[root] ?: 0) + 1
                }
            }
            val ambiguous = HashSet<String>()
            for ((key, counts) in titleSupport.entries.sortedBy { it.key }) {
                val best = counts.values.max()
                val leaders = counts.filterValues { it == best }.keys
                if (leaders.size == 1) {
                    sets.union(key, leaders.single(), allowTwoStrong = false)
                } else {
                    ambiguous += key
                }
            }
            // A tied title stays on its own: an unbound work sharing it would otherwise carry it into one side anyway.
            for (weak in weakOnly) {
                val joinable = weak.filterNot { it in ambiguous }
                for (i in 1 until joinable.size) {
                    sets.union(joinable[0], joinable[i], allowTwoStrong = false)
                }
            }
            return CommunityWorkGroups(sets.groupIds(), conflicts, ambiguous.size)
        }
    }

    /** Union-find that remembers, for every group, the one strong key it has on each site. */
    private class KeySets {

        private val parent = HashMap<String, String>()
        private val namespaces = HashMap<String, MutableMap<String, String>>()

        fun add(key: String) {
            if (key in parent) return
            parent[key] = key
            namespaces[key] = CommunityWorkKeys.namespace(key)?.let { mutableMapOf(it to key) } ?: mutableMapOf()
        }

        fun find(key: String): String {
            var root = key
            while (true) {
                val next = parent.getValue(root)
                if (next == root) break
                root = next
            }
            var node = key
            while (node != root) {
                val next = parent.getValue(node)
                parent[node] = root
                node = next
            }
            return root
        }

        fun union(a: String, b: String, allowTwoStrong: Boolean): Boolean {
            val rootA = find(a)
            val rootB = find(b)
            if (rootA == rootB) return true
            val left = namespaces.getValue(rootA)
            val right = namespaces.getValue(rootB)
            if (!allowTwoStrong && left.isNotEmpty() && right.isNotEmpty()) return false
            if (right.any { (namespace, key) -> left[namespace].let { it != null && it != key } }) return false
            // The smaller key stays the root, which keeps the build independent of the order unions happen in.
            val (root, child) = if (rootA < rootB) rootA to rootB else rootB to rootA
            parent[child] = root
            namespaces.getValue(root).putAll(namespaces.getValue(child))
            namespaces.remove(child)
            return true
        }

        fun groupIds(): Map<String, String> {
            val members = parent.keys.groupBy(::find)
            return buildMap {
                for (keys in members.values) {
                    val id = keys.filter(CommunityWorkKeys::isStrong).minOrNull() ?: keys.min()
                    keys.forEach { put(it, id) }
                }
            }
        }
    }
}
