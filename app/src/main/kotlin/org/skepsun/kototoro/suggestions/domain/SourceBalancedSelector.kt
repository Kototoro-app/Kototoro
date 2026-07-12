package org.skepsun.kototoro.suggestions.domain

internal fun <T, K> List<T>.selectBalancedBySource(
	limit: Int,
	perSourceLimit: Int,
	sourceOf: (T) -> K,
): List<T> {
	if (limit <= 0 || perSourceLimit <= 0 || isEmpty()) {
		return emptyList()
	}

	val queues = LinkedHashMap<K, ArrayDeque<T>>()
	for (item in this) {
		val queue = queues.getOrPut(sourceOf(item)) { ArrayDeque() }
		if (queue.size < perSourceLimit) {
			queue += item
		}
	}

	val result = ArrayList<T>(minOf(limit, size))
	while (result.size < limit) {
		var added = false
		for (queue in queues.values) {
			if (queue.isNotEmpty()) {
				result += queue.removeFirst()
				added = true
				if (result.size == limit) {
					break
				}
			}
		}
		if (!added) {
			break
		}
	}
	return result
}
