package org.skepsun.kototoro.entitygraph.data

/**
 * Pure planning logic for the deferred consolidation of provisional import entities
 * (phase 2 of the external backup fast-import). Given the set of WORK entities created
 * by the bulk import (bindings with created_by=IMPORT), produces merge groups:
 *  - union by strong projection keys ("source|location|url" / publicUrl) — same-source
 *    duplicates (mirror urls, renames);
 *  - union by normalized primary name + content type — cross-source duplicates of the
 *    same work, which strong keys can never catch (different sources imply different urls).
 *
 * Inputs are provisional entities only: entities that pre-date the import never enter a
 * group, so consolidation can never auto-merge curated user entities. Canonical selection
 * prefers the member whose nameHash is unsalted (the first record seen for that title),
 * then the lowest entity id for stable results.
 */
internal data class ConsolidationEntity(
    val entityId: Long,
    val contentType: String?,
    val primaryName: String,
    val nameHash: Long,
    val strongKeys: Set<String> = emptySet(),
)

internal data class ConsolidationGroup(
    val canonicalEntityId: Long,
    val absorbedEntityIds: List<Long>,
)

internal fun buildConsolidationGroups(
    entities: List<ConsolidationEntity>,
): List<ConsolidationGroup> {
    if (entities.size < 2) {
        return emptyList()
    }
    val byId = entities.associateBy { it.entityId }
    val disjointSet = ConsolidationDisjointSet(entities)

    val ownerByStrongKey = HashMap<String, Long>()
    for (entity in entities) {
        for (key in entity.strongKeys) {
            val previous = ownerByStrongKey.putIfAbsent(key, entity.entityId)
            if (previous != null && previous != entity.entityId) {
                disjointSet.union(previous, entity.entityId)
            }
        }
    }

    val ownerByTitleKey = HashMap<String, Long>()
    for (entity in entities) {
        val normalized = normalizeName(entity.primaryName)
        if (normalized.isEmpty()) {
            continue
        }
        val titleKey = normalized + "\u0000" + (entity.contentType ?: "")
        val previous = ownerByTitleKey.putIfAbsent(titleKey, entity.entityId)
        if (previous != null && previous != entity.entityId) {
            disjointSet.union(previous, entity.entityId)
        }
    }

    return disjointSet.groups()
        .map { memberIds ->
            val members = memberIds.mapNotNull(byId::get)
            val canonical = members.firstOrNull { it.nameHash == computeNameHash(it.primaryName) }
                ?: members.minBy { it.entityId }
            ConsolidationGroup(
                canonicalEntityId = canonical.entityId,
                absorbedEntityIds = members.map { it.entityId }.filter { it != canonical.entityId },
            )
        }
        .filter { it.absorbedEntityIds.isNotEmpty() }
}

private class ConsolidationDisjointSet(entities: List<ConsolidationEntity>) {
    private val parent = LinkedHashMap<Long, Long>().also { map ->
        entities.forEach { entity -> map[entity.entityId] = entity.entityId }
    }

    fun union(left: Long, right: Long) {
        val leftRoot = find(left)
        val rightRoot = find(right)
        if (leftRoot != rightRoot) {
            parent[rightRoot] = leftRoot
        }
    }

    fun groups(): List<List<Long>> {
        val grouped = LinkedHashMap<Long, MutableList<Long>>()
        parent.keys.forEach { entityId ->
            grouped.getOrPut(find(entityId)) { ArrayList() } += entityId
        }
        return grouped.values.toList()
    }

    private fun find(entityId: Long): Long {
        val current = parent[entityId] ?: return entityId
        if (current == entityId) {
            return current
        }
        val root = find(current)
        parent[entityId] = root
        return root
    }
}
