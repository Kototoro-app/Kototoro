package org.skepsun.kototoro.core.dictionary

object TranslationDictionaryPolicy {

    fun selectRelevant(text: String, pairs: List<DictPair>): List<DictPair> {
        val normalizedText = normalize(text)
        if (normalizedText.isBlank()) return emptyList()
        return pairs
            .asSequence()
            .filterNot(DictPair::excluded)
            .mapNotNull { pair ->
                val original = pair.original.trim()
                val translation = pair.translation.trim()
                if (original.isBlank() || translation.isBlank()) return@mapNotNull null
                pair.copy(original = original, translation = translation)
            }
            .filter { normalize(it.original) in normalizedText }
            .distinctBy { normalize(it.original) }
            .sortedByDescending { it.original.length }
            .toList()
    }

    fun mergeDiscovered(existing: List<DictPair>, discovered: List<DictPair>): List<DictPair> {
        val result = ArrayList<DictPair>(existing.size + discovered.size)
        val seen = HashSet<String>()
        fun append(pair: DictPair) {
            val original = pair.original.trim()
            val translation = pair.translation.trim()
            if (original.isBlank() || translation.isBlank()) return
            if (seen.add(normalize(original))) {
                result += pair.copy(original = original, translation = translation)
            }
        }
        existing.forEach(::append)
        discovered.forEach(::append)
        return result
    }

    fun applyManualEdits(current: List<DictPair>, edited: List<DictPair>): List<DictPair> {
        val active = mergeDiscovered(emptyList(), edited)
        val removed = current.map { it.copy(excluded = true) }
        return mergeDiscovered(active, removed)
    }

    private fun normalize(value: String): String = value.trim().lowercase()
}
