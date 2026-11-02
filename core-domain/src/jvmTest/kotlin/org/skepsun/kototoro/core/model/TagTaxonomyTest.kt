package org.skepsun.kototoro.core.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TagTaxonomyTest {

    @Test
    fun `the shared catalog carries permanent unique ids for every category`() {
        assertEquals(334, TagTaxonomy.tags.size)
        assertEquals(TagTaxonomy.tags.size, TagTaxonomy.tags.map(TaxonomyTag::id).distinct().size)
        assertTrue(TagTaxonomy.tags.all { it.category == TaxonomyCategory.fromTagId(it.id) })
        val populated = TaxonomyCategory.entries - TaxonomyCategory.RAW
        assertTrue(populated.all { category -> TagTaxonomy.tags.any { it.category == category } })
    }

    @Test
    fun `an alias resolves to one or more permanent ids without fuzzy matching`() {
        assertEquals(listOf("genre.ecchi"), TagTaxonomy.resolve("ecchi").map(TaxonomyTag::id))
        assertEquals(listOf("genre.ecchi"), TagTaxonomy.resolve("  ECC ").map(TaxonomyTag::id))
        assertEquals(
            setOf("narrative.reincarnation", "setting.other-world"),
            TagTaxonomy.resolve("Reincarnated in Another World").map(TaxonomyTag::id).toSet(),
            "one alias may describe several permanent tags",
        )
        assertEquals(listOf("genre.martial-arts"), TagTaxonomy.resolve("artes marciais").map(TaxonomyTag::id))
        assertTrue(TagTaxonomy.resolve("act").isEmpty(), "partial words never match")
        assertTrue(TagTaxonomy.resolve(" ").isEmpty())
    }

    @Test
    fun `source vocabulary and the safety flags come with the data`() {
        assertTrue("ecchi" in TagTaxonomy.knownSourceTags)
        assertTrue(TagTaxonomy.find("warning.gore")!!.sensitive)
        assertFalse(TagTaxonomy.find("genre.action")!!.sensitive)
        assertTrue(TagTaxonomy.find("genre.erotica")!!.adult)
        assertTrue(TagTaxonomy.find("audience.adult")!!.adult)
        assertTrue(TagTaxonomy.find("genre.ecchi")!!.aliases.contains("ecc"))
    }

    @Test
    fun `labels localise from the language tag instead of a platform locale`() {
        val tag = TagTaxonomy.find("theme.revenge")!!
        assertEquals("Revenge", tag.localizedLabel("en"))
        assertEquals("复仇", tag.localizedLabel("zh"))
        assertEquals("Revenge", tag.localizedLabel("fr"), "only Chinese switches to the Chinese label")
        assertEquals("Content warning", TaxonomyCategory.WARNING.localizedLabel("en"))
        assertEquals("内容提示", TaxonomyCategory.WARNING.localizedLabel("zh"))
    }

    @Test
    fun `traditional labels stay simplified until a platform installs its converter`() {
        val tag = TagTaxonomy.find("theme.revenge")!!
        assertEquals(tag.chineseLabel, tag.traditionalChineseLabel)
        assertEquals("复仇", tag.localizedLabel("zh", traditional = true))
    }

    @Test
    fun `a converter installed after the first lookup still relabels the cached catalog`() {
        // The catalog is read first, exactly like a shared deriver that runs before Android's façade is touched.
        assertEquals("轻度情色", TagTaxonomy.find("genre.ecchi")!!.chineseLabel)
        assertTrue(TagTaxonomy.resolve("輕度情色").isEmpty())
        val converter = TagTaxonomy.traditionalLabelConverter
        try {
            TagTaxonomy.traditionalLabelConverter = { it.replace('轻', '輕').replace('复', '復').replace('愤', '憤') }
            assertEquals("輕度情色", TagTaxonomy.find("genre.ecchi")!!.traditionalChineseLabel)
            assertEquals("genre.ecchi", TagTaxonomy.resolve("輕度情色").single().id)
            assertEquals("genre.ecchi", TagTaxonomy.resolve("轻度情色").single().id)
        } finally {
            TagTaxonomy.traditionalLabelConverter = converter
        }
        // Restoring the converter puts the fallback labels back, so the shared test JVM never leaks Hant labels.
        assertEquals("轻度情色", TagTaxonomy.find("genre.ecchi")!!.traditionalChineseLabel)
        assertTrue(TagTaxonomy.resolve("輕度情色").isEmpty())
    }

    @Test
    fun `search spans ids, labels and aliases`() {
        val revenge = TagTaxonomy.find("theme.revenge")!!
        assertTrue(TagTaxonomy.search(revenge, "revenge"))
        assertTrue(TagTaxonomy.search(revenge, "复仇"))
        assertTrue(TagTaxonomy.search(revenge, "vengeance"))
        assertTrue(TagTaxonomy.search(revenge, " "), "an empty query keeps every tag")
        assertFalse(TagTaxonomy.search(revenge, "horror"))
    }
}
