package org.skepsun.kototoro.core.model

import org.skepsun.kototoro.core.util.ChineseConverter
import java.util.Locale

private val TRADITIONAL_CHINESE_REGIONS = setOf("HK", "MO", "TW")

private fun Locale.usesTraditionalChinese(): Boolean =
    language == Locale.CHINESE.language &&
        (script.equals("Hant", ignoreCase = true) || country.uppercase(Locale.ROOT) in TRADITIONAL_CHINESE_REGIONS)

/**
 * Android's view of the shared [TagTaxonomy]: the catalog, its alias index and the matching rules live in
 * `core-domain`, and the single platform input is the simplifier→traditional converter installed here — the object
 * is the only entry point Android has to the catalog, so the converter is in place before the tags are first read.
 * Locale-aware names stay in this module because `java.util.Locale` is Android/JVM-only.
 */
object KototoroTaxonomy {

    init {
        TagTaxonomy.traditionalLabelConverter = ChineseConverter::s2t
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
