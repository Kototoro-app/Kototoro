package org.skepsun.kototoro.core.model

import org.skepsun.kototoro.core.util.ChineseConverter
import java.util.Locale

private val TRADITIONAL_CHINESE_REGIONS = setOf("HK", "MO", "TW")

private fun Locale.usesTraditionalChinese(): Boolean =
    language == Locale.CHINESE.language &&
        (script.equals("Hant", ignoreCase = true) || country.uppercase(Locale.ROOT) in TRADITIONAL_CHINESE_REGIONS)

private val TRADITIONAL_CHINESE: (String) -> String = ChineseConverter::s2t

/**
 * Android's only platform input to the shared catalog: the simplifier→traditional converter. The shared rules may
 * read the catalog before any screen does, so [org.skepsun.kototoro.core.BaseApp] installs it at startup; the façade
 * installs it again on first use, and a late installation rebuilds the shared label and alias index.
 */
internal fun installTraditionalLabels() {
    if (TagTaxonomy.traditionalLabelConverter !== TRADITIONAL_CHINESE) {
        TagTaxonomy.traditionalLabelConverter = TRADITIONAL_CHINESE
    }
}

/**
 * Android's view of the shared [TagTaxonomy]: the catalog, its alias index and the matching rules live in
 * `core-domain`. Locale-aware names stay in this module because `java.util.Locale` is Android/JVM-only.
 */
object KototoroTaxonomy {

    init {
        installTraditionalLabels()
    }

    val tags: List<TaxonomyTag> get() = TagTaxonomy.tags

    val knownSourceTags: List<String> get() = TagTaxonomy.knownSourceTags

    fun find(id: String): TaxonomyTag? = TagTaxonomy.find(id)

    fun resolve(rawTag: String): Set<TaxonomyTag> = TagTaxonomy.resolve(rawTag)

    fun search(tag: TaxonomyTag, query: String): Boolean = TagTaxonomy.search(tag, query)

    internal fun normalize(value: String): String = TagTaxonomy.normalize(value)
}

fun TaxonomyCategory.displayName(locale: Locale = Locale.getDefault()): String =
    localizedLabel(locale.language, locale.usesTraditionalChinese())

fun TaxonomyTag.displayName(locale: Locale = Locale.getDefault()): String =
    localizedLabel(locale.language, locale.usesTraditionalChinese())
