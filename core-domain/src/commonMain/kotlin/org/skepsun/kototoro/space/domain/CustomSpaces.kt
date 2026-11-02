package org.skepsun.kototoro.space.domain

import org.skepsun.kototoro.space.data.SpaceDefinitionEntity

/**
 * A user-defined Space as Android stores it in `space_definitions`: content type names, source languages and source
 * kinds (Android's `SourceType` names), each comma-separated. AND between dimensions, OR within one, an empty
 * dimension unrestricted — the rule `docs/architecture/custom-spaces.md` describes. Shared so a Space made on one
 * platform means the same on another.
 */
data class CustomSpace(
    val id: String,
    val title: String,
    val sortKey: Int,
    val enabled: Boolean,
    val contentTypes: Set<String>,
    val languages: Set<String>,
    val sourceKinds: Set<String>,
) {
    /** The media kind the space shows (MANGA, NOVEL or VIDEO), or null when its types span several. */
    val mediaKind: String?
        get() = contentTypes.map(::spaceMediaKind).toSet().singleOrNull()

    /** Whether a source of [contentType], [locale] and Android source kind [sourceKind] belongs to the space. */
    fun admits(contentType: String, locale: String?, sourceKind: String): Boolean =
        (contentTypes.isEmpty() || contentType in contentTypes) &&
            (languages.isEmpty() || normalizeSpaceLanguage(locale) in languages) &&
            (sourceKinds.isEmpty() || sourceKind in sourceKinds)

    fun toEntity(createdAt: Long, updatedAt: Long) = SpaceDefinitionEntity(
        spaceId = id, title = title.trim(), sortKey = sortKey, enabled = enabled,
        contentTypes = contentTypes.sorted().joinToString(","),
        sourceLanguages = languages.map(::normalizeSpaceLanguage).sorted().joinToString(","),
        sourceKinds = sourceKinds.sorted().joinToString(","),
        createdAt = createdAt, updatedAt = updatedAt, deletedAt = 0L,
    )

    companion object {
        const val ID_PREFIX = "custom:"
        /** Android's limit on custom Spaces. */
        const val MAX = 12

        fun from(entity: SpaceDefinitionEntity) = CustomSpace(
            id = entity.spaceId, title = entity.title, sortKey = entity.sortKey, enabled = entity.enabled,
            contentTypes = entity.contentTypes.decodeList(),
            languages = entity.sourceLanguages.decodeList().map(::normalizeSpaceLanguage).toSet(),
            sourceKinds = entity.sourceKinds.decodeList(),
        )
    }
}

/** MANGA, NOVEL or VIDEO for an Android content type name (`HENTAI_` prefix allowed). */
fun spaceMediaKind(contentType: String): String = contentType.removePrefix("HENTAI_").let {
    if (it == "NOVEL" || it == "VIDEO") it else "MANGA"
}

/** Lower-case ISO language of a source locale (`zh-Hans` → `zh`); sources without one are `""` (multilingual). */
fun normalizeSpaceLanguage(locale: String?): String =
    locale?.trim()?.lowercase()?.substringBefore('-')?.substringBefore('_').orEmpty()

private fun String.decodeList(): Set<String> = split(',').mapNotNullTo(LinkedHashSet()) { it.trim().ifEmpty { null } }
