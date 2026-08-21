package org.skepsun.kototoro.search.domain

import androidx.annotation.StringRes
import androidx.annotation.DrawableRes
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.jsonsource.SourceType
import org.skepsun.kototoro.explore.ui.model.SourceTag

val ALL_SOURCE_TYPES: Set<SourceType> = setOf(
    SourceType.NATIVE,
    SourceType.JSON_LEGADO,
    SourceType.JSON_TVBOX,
    SourceType.MIHON,
    SourceType.ANIYOMI,
    SourceType.IREADER,
    SourceType.CLOUDSTREAM,
    SourceType.JSON_LNREADER,
)

data class SourceTypeOption(
    val type: SourceType,
    @StringRes val titleRes: Int,
    @DrawableRes val iconRes: Int,
)

val SOURCE_TYPE_OPTIONS: List<SourceTypeOption> = listOf(
    SourceTypeOption(SourceType.NATIVE, R.string.source_type_native, R.drawable.ic_source_builtin),
    SourceTypeOption(SourceType.MIHON, R.string.source_type_mihon, R.drawable.ic_source_mihon),
    SourceTypeOption(SourceType.ANIYOMI, R.string.source_type_aniyomi, R.drawable.ic_source_aniyomi),
    SourceTypeOption(SourceType.JSON_LEGADO, R.string.source_type_legado, R.drawable.ic_source_legado),
    SourceTypeOption(SourceType.JSON_TVBOX, R.string.source_type_tvbox, R.drawable.ic_source_tvbox),
    SourceTypeOption(SourceType.IREADER, R.string.source_type_ireader, R.drawable.ic_source_ireader),
    SourceTypeOption(SourceType.CLOUDSTREAM, R.string.source_type_cloudstream, R.drawable.ic_source_cloudstream),
    SourceTypeOption(SourceType.JSON_LNREADER, R.string.source_type_lnreader, R.drawable.ic_source_lnreader),
)

fun sourceTypesFromTags(tags: Set<SourceTag>): Set<SourceType> {
    if (tags.isEmpty()) {
        return ALL_SOURCE_TYPES
    }
    val result = mutableSetOf<SourceType>()
    tags.forEach { tag ->
        when (tag) {
            SourceTag.BUILTIN -> result.add(SourceType.NATIVE)
            SourceTag.MIHON -> result.add(SourceType.MIHON)
            SourceTag.ANIYOMI -> result.add(SourceType.ANIYOMI)
            SourceTag.LEGADO -> result.add(SourceType.JSON_LEGADO)

            SourceTag.TVBOX -> result.add(SourceType.JSON_TVBOX)
            SourceTag.IREADER -> result.add(SourceType.IREADER)
            SourceTag.CLOUDSTREAM -> result.add(SourceType.CLOUDSTREAM)
            SourceTag.LNREADER -> result.add(SourceType.JSON_LNREADER)
            SourceTag.PINNED -> result.addAll(ALL_SOURCE_TYPES)
        }
    }
    return if (result.isEmpty()) ALL_SOURCE_TYPES else result
}

fun sourceTypesFromNames(names: Collection<String>?): Set<SourceType>? {
    if (names.isNullOrEmpty()) return null
    val types = names.mapNotNull { name ->
        runCatching { SourceType.valueOf(name) }.getOrNull()
    }.toSet()
    val filtered = types.intersect(ALL_SOURCE_TYPES)
    return filtered.ifEmpty { null }
}

fun sourceTypesToNames(types: Set<SourceType>): ArrayList<String> {
    return ArrayList(types.map { it.name })
}
