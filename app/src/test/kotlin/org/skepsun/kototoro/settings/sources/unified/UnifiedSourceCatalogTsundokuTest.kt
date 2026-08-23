package org.skepsun.kototoro.settings.sources.unified

import android.content.Context
import eu.kanade.tachiyomi.source.Source
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import kotlinx.serialization.json.Json
import org.skepsun.kototoro.aniyomi.AniyomiExtensionManager
import org.skepsun.kototoro.cloudstream.runtime.CloudstreamRuntimeManager
import org.skepsun.kototoro.core.db.MangaDatabase
import org.skepsun.kototoro.core.jsonsource.JsonSourceManager
import org.skepsun.kototoro.core.prefs.AppSettings
import org.skepsun.kototoro.explore.data.ContentSourcesRepository
import org.skepsun.kototoro.explore.data.SourceAvailabilityRepository
import org.skepsun.kototoro.extensions.repo.ExternalExtensionRepoRepository
import org.skepsun.kototoro.ireader.IReaderExtensionManager
import org.skepsun.kototoro.mihon.MihonExtensionManager
import org.skepsun.kototoro.parsers.model.ContentSource
import org.skepsun.kototoro.tsundoku.TsundokuExtensionManager
import org.skepsun.kototoro.tsundoku.model.TsundokuNovelSource

/**
 * Phase 3A (T3A.5) — Tsundoku novel sources enter the unified source catalog.
 *
 * The catalog's observable graph (`observeSources`) combines Room invalidation,
 * `AppSettings.observe` key flows and APK-manager `changes` StateFlows — not unit-testable
 * end-to-end on a plain JVM. So this test pins the smallest honest seams that T3A.5 added:
 *
 *  - [UnifiedSourceCatalogRepository.getTsundokuApkSources] (the internal seam backing
 *    `getInstalledApkSources`) returns the wrapped Tsundoku novel source when the manager
 *    reports one, and nothing otherwise;
 *  - [UnifiedSourceCatalogRepository.toUnifiedSourceItem] maps that source to
 *    `kind = TSUNDOKU` with the `TSUNDOKU_{id}` key and a `package:TSUNDOKU:*` package ref
 *    — exactly what the unified source management UI renders.
 *
 * Sibling APK managers are intentionally not exercised: in this module's JVM test runtime,
 * their final methods fall through to real bodies (NPE on the uninitialized facade), so no
 * mock of them can be recorded. The Tsundoku manager, like the Mihon one, stubs reliably.
 */
class UnifiedSourceCatalogTsundokuTest : FunSpec({

    test("catalog installed-apk pool includes the Tsundoku source when the manager reports one") {
        val source = tsundokuSource()
        val tsundokuManager = mockk<TsundokuExtensionManager>(relaxed = true)
        every { tsundokuManager.getTsundokuNovelSources() } returns listOf(source)

        val repository = testTsundokuRepository(tsundokuManager)

        repository.getTsundokuApkSources().map { it.name } shouldBe listOf("TSUNDOKU_42")
    }

    test("catalog installed-apk pool has no TSUNDOKU_ entries when the manager reports none") {
        val tsundokuManager = mockk<TsundokuExtensionManager>(relaxed = true)
        every { tsundokuManager.getTsundokuNovelSources() } returns emptyList()

        val repository = testTsundokuRepository(tsundokuManager)

        repository.getTsundokuApkSources().any { it.name.startsWith("TSUNDOKU_") } shouldBe false
    }

    test("Tsundoku source item resolves to kind=TSUNDOKU with TSUNDOKU_ key and package ref") {
        val repository = testTsundokuRepository(mockk<TsundokuExtensionManager>(relaxed = true))

        val item = repository.invokeToUnifiedSourceItem(tsundokuSource())

        item.kind shouldBe UnifiedSourceKind.TSUNDOKU
        item.id shouldBe "TSUNDOKU_42"
        item.packageId shouldBe "package:TSUNDOKU:org.tsundoku.test"
        item.packageName shouldBe "org.tsundoku.test"
    }
})

private fun tsundokuSource(): TsundokuNovelSource {
    return TsundokuNovelSource(
        upstreamSource = object : Source {
            override val id: Long = 42L
            override val name: String = "Example Novel"
            override val lang: String = "en"
        },
        pkgName = "org.tsundoku.test",
        isNsfw = false,
    )
}

private fun testTsundokuRepository(
    tsundokuExtensionManager: TsundokuExtensionManager,
): UnifiedSourceCatalogRepository {
    return UnifiedSourceCatalogRepository(
        appContext = mockk<Context>(relaxed = true),
        localizedContext = mockk<Context>(relaxed = true),
        database = mockk<MangaDatabase>(relaxed = true),
        settings = mockk<AppSettings>(relaxed = true),
        contentSourcesRepository = mockk<ContentSourcesRepository>(relaxed = true),
        sourceAvailabilityRepository = mockk<SourceAvailabilityRepository>(relaxed = true),
        jsonSourceManager = mockk<JsonSourceManager>(relaxed = true),
        extensionRepoRepository = mockk<ExternalExtensionRepoRepository>(relaxed = true),
        mihonExtensionManager = mockk<MihonExtensionManager>(relaxed = true),
        aniyomiExtensionManager = mockk<AniyomiExtensionManager>(relaxed = true),
        ireaderExtensionManager = mockk<IReaderExtensionManager>(relaxed = true),
        tsundokuExtensionManager = tsundokuExtensionManager,
        cloudstreamRuntimeManager = mockk<CloudstreamRuntimeManager>(relaxed = true),
        json = Json,
    )
}

private fun UnifiedSourceCatalogRepository.invokeToUnifiedSourceItem(source: ContentSource): UnifiedSourceItem {
    val method = javaClass.getDeclaredMethod(
        "toUnifiedSourceItem",
        ContentSource::class.java,
        org.skepsun.kototoro.core.db.entity.MangaSourceEntity::class.java,
        org.skepsun.kototoro.core.db.entity.JsonSourceSummary::class.java,
        org.skepsun.kototoro.core.db.entity.JsonSourceEntity::class.java,
    )
    method.isAccessible = true
    return method.invoke(this, source, null, null, null) as UnifiedSourceItem
}
