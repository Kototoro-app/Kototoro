package org.skepsun.kototoro.tsundoku

import android.content.Context
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.tsundoku.model.TsundokuNovelSource

class TsundokuExtensionManagerTest {

    @Test
    fun `manager resolves TSUNDOKU sources after load`() = runBlocking {
        val novel = FxNovelSource()
        val loader = mockk<TsundokuExtensionLoader>(relaxed = true)
        coEvery { loader.loadExtensions(any()) } returns listOf(
            TsundokuLoadResult.Success(
                pkgName = "eu.kanade.tachiyomi.novelextension.en.fixture",
                appName = "Fixture",
                versionCode = 1,
                versionName = "1.4.1",
                libVersion = 1.4,
                lang = "en",
                isNsfw = false,
                sources = listOf(novel),
            ),
        )

        val manager = TsundokuExtensionManager(
            context = mockk<Context>(relaxed = true),
            loader = loader,
        )
        manager.loadExtensions()

        val wrapped = manager.getTsundokuNovelSources().single()
        assertTrue(wrapped is TsundokuNovelSource)
        assertEquals("TSUNDOKU_9001", wrapped.name)
        assertSame(novel, (wrapped as TsundokuNovelSource).upstreamSource)
        assertSame(wrapped, manager.resolveSource("TSUNDOKU_9001"))
        assertSame(wrapped, manager.resolveSourceById(9001L))
        assertEquals(novel, manager.getSourceById(9001L))
        assertTrue(manager.hasExtensions())
        assertNull(manager.resolveSource("TSUNDOKU_9999"))
        assertNull(manager.resolveSource("MIHON_1"))
        assertNull(manager.resolveSource("not-a-tsundoku-key"))
    }

    @Test
    fun `manager surfaces structured load failures`() = runBlocking {
        val loader = mockk<TsundokuExtensionLoader>(relaxed = true)
        coEvery { loader.loadExtensions(any()) } returns listOf(
            TsundokuLoadResult.Error(
                pkgName = "eu.kanade.tachiyomi.novelextension.en.broken",
                phase = "AMBIGUOUS",
                message = "ambiguous extension refused",
            ),
        )

        val manager = TsundokuExtensionManager(
            context = mockk<Context>(relaxed = true),
            loader = loader,
        )
        manager.loadExtensions()

        assertEquals(0, manager.getSourceCount())
        val failed = manager.failedExtensions.value.single()
        assertEquals("AMBIGUOUS", failed.phase)
        assertEquals("eu.kanade.tachiyomi.novelextension.en.broken", failed.pkgName)
    }
}
