package org.skepsun.kototoro.mihon.compat

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.content.res.AssetManager
import android.content.res.Resources
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.Source
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.extensions.runtime.tachiyomi.remapTachiyomiPreferenceKey
import org.skepsun.kototoro.mihon.model.MihonMangaSource
import org.skepsun.kototoro.parsers.model.ContentSource
import org.skepsun.kototoro.tsundoku.model.TsundokuNovelSource

/**
 * T3B.5 — Tsundoku preference isolation.
 *
 *  - the pure remap function [remapTachiyomiPreferenceKey] isolates `source_<id>` for Tsundoku
 *    sources (shared with Mihon, so same-id extensions would otherwise contaminate each other)
 *    and is a no-op for Mihon / Aniyomi / unknown / null sources;
 *  - the delegating [NamespacedApplication] (what `KotoInjektBridge` registers as the Injekt
 *    `Application` singleton) forwards `getSharedPreferences` to the real application with the
 *    remapped name while a Tsundoku source is active in [MihonRequestContext], and otherwise
 *    reaches the real application unchanged.
 */
class TachiyomiPreferenceNamespaceTest {

    // --- pure remap function ---

    @Test
    fun `tsundoku source remaps source pref key with package`() {
        val key = remapTachiyomiPreferenceKey("source_123", tsundokuSource())
        assertEquals("source_tsundoku_com.example.novel_123", key)
    }

    @Test
    fun `tsundoku source uses package tail segment after colon`() {
        val key = remapTachiyomiPreferenceKey("source_123", tsundokuSource(pkg = "com.example.group:novel"))
        assertEquals("source_tsundoku_novel_123", key)
    }

    @Test
    fun `tsundoku source package tail is truncated to 64 chars`() {
        val longPkg = "com.example." + "p".repeat(80)
        val key = remapTachiyomiPreferenceKey("source_123", tsundokuSource(pkg = longPkg))
        assertEquals("source_tsundoku_${longPkg.takeLast(64)}_123", key)
    }

    @Test
    fun `tsundoku source leaves non source pref keys unchanged`() {
        assertEquals("user_agent", remapTachiyomiPreferenceKey("user_agent", tsundokuSource()))
    }

    @Test
    fun `mihon source keeps raw pref key`() {
        assertEquals("source_123", remapTachiyomiPreferenceKey("source_123", mihonSource()))
    }

    @Test
    fun `null source keeps raw pref key`() {
        assertEquals("source_123", remapTachiyomiPreferenceKey("source_123", null))
    }

    @Test
    fun `non adapter source keeps raw pref key`() {
        val plain = mockk<ContentSource>(relaxed = true)
        assertEquals("source_123", remapTachiyomiPreferenceKey("source_123", plain))
    }

    // --- NamespacedApplication (delegating Application registered through KotoInjektBridge) ---

    @Test
    fun `namespaced application forwards remapped pref name while tsundoku source is active`() {
        val real = mockk<Application>()
        val prefs = mockk<SharedPreferences>(relaxed = true)
        every { real.getSharedPreferences(any(), any()) } returns prefs
        val wrapper = NamespacedApplication(real)

        val result = MihonRequestContext.withSourceBlocking(tsundokuSource()) {
            wrapper.getSharedPreferences("source_123", Context.MODE_PRIVATE)
        }

        assertSame(prefs, result)
        verify(exactly = 1) {
            real.getSharedPreferences("source_tsundoku_com.example.novel_123", Context.MODE_PRIVATE)
        }
    }

    @Test
    fun `namespaced application forwards raw pref name while mihon source is active`() {
        val real = mockk<Application>()
        val prefs = mockk<SharedPreferences>(relaxed = true)
        every { real.getSharedPreferences(any(), any()) } returns prefs
        val wrapper = NamespacedApplication(real)

        MihonRequestContext.withSourceBlocking(mihonSource()) {
            wrapper.getSharedPreferences("source_123", Context.MODE_PRIVATE)
        }

        verify(exactly = 1) { real.getSharedPreferences("source_123", Context.MODE_PRIVATE) }
    }

    @Test
    fun `namespaced application forwards raw pref name without source context`() {
        val real = mockk<Application>()
        val prefs = mockk<SharedPreferences>(relaxed = true)
        every { real.getSharedPreferences(any(), any()) } returns prefs
        val wrapper = NamespacedApplication(real)

        wrapper.getSharedPreferences("source_123", Context.MODE_PRIVATE)

        verify(exactly = 1) { real.getSharedPreferences("source_123", Context.MODE_PRIVATE) }
    }

    @Test
    fun `namespaced application forwards application context`() {
        val real = mockk<Application>()
        every { real.applicationContext } returns real
        val wrapper = NamespacedApplication(real)

        assertSame(real, wrapper.applicationContext)
    }

    @Test
    fun `namespaced application exposes real as base context`() {
        val real = mockk<Application>()
        val wrapper = NamespacedApplication(real)

        assertSame(real, wrapper.baseContext)
    }

    @Test
    fun `namespaced application forwards resources and assets`() {
        val real = mockk<Application>()
        val resources = mockk<Resources>(relaxed = true)
        val assets = mockk<AssetManager>(relaxed = true)
        every { real.resources } returns resources
        every { real.assets } returns assets
        val wrapper = NamespacedApplication(real)

        assertSame(resources, wrapper.resources)
        assertSame(assets, wrapper.assets)
    }

    private fun tsundokuSource(
        pkg: String = "com.example.novel",
        id: Long = 123L,
    ): TsundokuNovelSource {
        val upstream = mockk<Source>(relaxed = true)
        every { upstream.id } returns id
        return TsundokuNovelSource(
            upstreamSource = upstream,
            pkgName = pkg,
        )
    }

    private fun mihonSource(id: Long = 123L): MihonMangaSource {
        val catalogue = mockk<CatalogueSource>(relaxed = true)
        every { catalogue.id } returns id
        return MihonMangaSource(
            catalogueSource = catalogue,
            pkgName = "com.example.mihon",
        )
    }
}
