package org.skepsun.kototoro.suggestions.domain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PopularSourcesTest {

    private fun probe(title: String, name: String = "MIHON_1", host: String? = null) = PopularSourceProbe(name, title, host)

    @Test
    fun `titles hosts and enum names are matched after normalising`() {
        assertTrue(PopularSources.isPopular(probe("MangaFire")))
        assertTrue(PopularSources.isPopular(probe("Weeb Central (EN)")))
        assertTrue(PopularSources.isPopular(probe("Something", host = "https://www.mangafire.to/home")))
        assertTrue(PopularSources.isPopular(probe("x", name = "MANGAKATANA")))
    }

    @Test
    fun `short keys need an exact match so lookalikes stay out`() {
        assertTrue(PopularSources.isPopular(probe("MangaK", host = "mangak.io")))
        assertFalse(PopularSources.isPopular(probe("MangaKakalot")))
        assertFalse(PopularSources.isPopular(probe("Anvil Comics")))
        assertFalse(PopularSources.isPopular(probe("Comics Hub")))
    }

    @Test
    fun `chinese titles match and age needs an exact label`() {
        assertTrue(PopularSources.isPopular(probe("拷贝漫画")))
        assertTrue(PopularSources.isPopular(probe("笔趣阁 (中文)")))
        assertTrue(PopularSources.isPopular(probe("x", host = "https://www.agefans.cc")))
        assertTrue(PopularSources.isPopular(probe("AGE")))
        assertFalse(PopularSources.isPopular(probe("Teenage Dreams")))
    }

    @Test
    fun `favourite sources are the most saved ones and join the preferred set`() {
        fun work(source: String?, saved: Boolean = true) = LibraryWork(listOf("t"), emptyList(), isFavourite = saved, source = source)
        val library = listOf(work("A"), work("B"), work("A"), work("C", saved = false), work(null))
        assertEquals(listOf("A", "B"), PopularSources.favouriteSources(library).toList())
        assertEquals(setOf("A"), PopularSources.favouriteSources(library, limit = 1))
        assertEquals(setOf("P", "A"), PopularSources.preferred(false, setOf("P"), setOf("X"), emptyList(), setOf("A", "X")))
    }

    @Test
    fun `preferred sources add the ranked ones only when the switch is on and never an excluded one`() {
        val sources = listOf(probe("MangaFire", "A"), probe("Obscure", "B"), probe("Toonily", "C"))
        assertEquals(setOf("A", "C", "P"), PopularSources.preferred(true, setOf("P"), emptySet(), sources))
        assertEquals(setOf("P"), PopularSources.preferred(false, setOf("P"), emptySet(), sources))
        assertEquals(setOf("A"), PopularSources.preferred(true, emptySet(), setOf("C"), sources))
    }
}
