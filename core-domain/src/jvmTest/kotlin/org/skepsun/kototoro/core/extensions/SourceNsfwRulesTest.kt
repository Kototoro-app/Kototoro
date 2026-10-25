package org.skepsun.kototoro.core.extensions

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class SourceNsfwRulesTest {

    @Test
    fun `selection action resolves uniform and mixed cases`() {
        assertEquals(SelectedSourcesNsfwAction.NONE, resolveSelectedSourcesNsfwAction(0, 0))
        assertEquals(SelectedSourcesNsfwAction.NONE, resolveSelectedSourcesNsfwAction(1, 0))
        assertEquals(SelectedSourcesNsfwAction.SET_NSFW, resolveSelectedSourcesNsfwAction(0, 3))
        assertEquals(SelectedSourcesNsfwAction.SET_SFW, resolveSelectedSourcesNsfwAction(3, 3))
        assertEquals(SelectedSourcesNsfwAction.SET_SFW, resolveSelectedSourcesNsfwAction(4, 3))
        assertEquals(SelectedSourcesNsfwAction.CHOOSE, resolveSelectedSourcesNsfwAction(1, 3))
        assertEquals(SelectedSourcesNsfwAction.CHOOSE, resolveSelectedSourcesNsfwAction(2, 5))
    }

    @Test
    fun `an empty override set leaves the metadata in charge`() {
        assertNull(SourceNsfwOverrideSet().resolve("ANY_SOURCE"))
    }

    @Test
    fun `an override forces both directions`() {
        val overrides = SourceNsfwOverrideSet(nsfw = setOf("PLAIN"), sfw = setOf("HENTAI_SOURCE"))

        assertEquals(true, overrides.resolve("PLAIN"))
        assertEquals(false, overrides.resolve("HENTAI_SOURCE"))
        assertNull(overrides.resolve("OTHER"))
    }

    @Test
    fun `a source in both sets is forced SFW`() {
        assertEquals(false, SourceNsfwOverrideSet(nsfw = setOf("BOTH"), sfw = setOf("BOTH")).resolve("BOTH"))
    }

    @Test
    fun `writing an override moves the names and never keeps both`() {
        val sfw = SourceNsfwOverrideSet(nsfw = emptySet(), sfw = setOf("A", "B"))

        val nsfw = sfw.withOverride(listOf("A", "C"), true)
        assertEquals(setOf("A", "C"), nsfw.nsfw)
        assertEquals(setOf("B"), nsfw.sfw)

        val retired = nsfw.withOverride(listOf("A"), null)
        assertEquals(setOf("C"), retired.nsfw)
        assertEquals(setOf("B"), retired.sfw)
    }

    @Test
    fun `blank names and empty batches leave the sets untouched`() {
        val overrides = SourceNsfwOverrideSet(nsfw = setOf("A"))

        assertEquals(overrides, overrides.withOverride(listOf(" ", ""), true))
        assertEquals(overrides, overrides.withOverride(emptyList(), false))
    }

    @Test
    fun `only the sources whose flag differs are persisted`() {
        val flags = mapOf("A" to true, "B" to false, "C" to true)

        assertEquals(setOf("B"), changedNsfwSources(listOf("A", "B", "C"), true) { flags.getValue(it) })
        assertEquals(setOf("A", "C"), changedNsfwSources(listOf("A", "B", "C"), false) { flags.getValue(it) })
        assertEquals(emptySet<String>(), changedNsfwSources(listOf("A"), true) { flags.getValue(it) })
    }
}
