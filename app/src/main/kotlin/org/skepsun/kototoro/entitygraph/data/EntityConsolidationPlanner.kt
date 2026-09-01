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
 *
 * @see buildAnchorAbsorptionGroups for the other half of the picture: provisional
 * entities that duplicate a work the library already had.
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

/** Identity of "the same work" for title-based matching: normalized title + content type. */
internal fun consolidationTitleKey(contentType: String?, primaryName: String): String =
    normalizeName(primaryName) + "\u0000" + (contentType ?: "")

/**
 * Absorb provisional entities into a pre-existing entity that already owns the same work.
 *
 * The bulk import only attaches a record to an entity it can see up front; when two
 * records of the same title arrive in one batch the second one is stored under a *salted*
 * name_hash so it survives the unique index, and when an import ran before (or a previous
 * consolidation failed) an entity may simply exist twice. Those leftovers carry
 * `created_by = IMPORT`, so they are provisional forever: they never merge with anything
 * that pre-dates them and the user keeps seeing the same work twice in a category
 * (issue #510).
 *
 * Only provisional entities are absorbed here — the anchor is always kept, so this can
 * never merge two entities the user curated themselves.
 */
internal fun buildAnchorAbsorptionGroups(
    entities: List<ConsolidationEntity>,
    anchorIdByTitleKey: Map<String, Long>,
): List<ConsolidationGroup> {
    if (entities.isEmpty() || anchorIdByTitleKey.isEmpty()) {
        return emptyList()
    }
    val absorbedByAnchor = LinkedHashMap<Long, MutableList<Long>>()
    for (entity in entities) {
        if (normalizeName(entity.primaryName).isEmpty()) {
            continue
        }
        val anchorId = anchorIdByTitleKey[consolidationTitleKey(entity.contentType, entity.primaryName)]
            ?: continue
        if (anchorId == entity.entityId) {
            continue
        }
        absorbedByAnchor.getOrPut(anchorId) { ArrayList() } += entity.entityId
    }
    return absorbedByAnchor.map { (anchorId, absorbed) ->
        ConsolidationGroup(canonicalEntityId = anchorId, absorbedEntityIds = absorbed.sorted())
    }
}

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
