package org.skepsun.kototoro.settings.sources.extensions

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.skepsun.kototoro.aniyomi.AniyomiExtensionManager
import org.skepsun.kototoro.extensions.repo.ExternalExtensionType
import org.skepsun.kototoro.ireader.IReaderExtensionManager
import org.skepsun.kototoro.mihon.MihonExtensionManager

/**
 * T3A.6 guard — the legacy extensions browser must stay EMPTY for TSUNDOKU.
 *
 * Completion gate: Tsundoku gets its entry points only in the unified source management UI
 * ([UnifiedSourceKind.TSUNDOKU] label/icon mappings in [SourcesSettingsRoute] and
 * [UnifiedSourcesDisplay]), while the legacy per-ecosystem extension browser keeps TSUNDOKU
 * unregistered — [ExtensionsBrowserViewModel.getSourcesForPackage] returns `emptyList()` for
 * `ExternalExtensionType.TSUNDOKU` and [ExternalExtensionPresentation] emits an empty map/list.
 * Result: no duplicate Tsundoku entry in the old browser.
 *
 * Asserted at the presentation layer without instantiating any ViewModel: for
 * [ExternalExtensionType.TSUNDOKU], [observeInstalledExtensionInfoMap] emits an empty map and
 * [observeInstalledExtensionEntries] emits an empty list — i.e. the browser has no Tsundoku
 * "available / installed / repository" rows to render.
 */
class ExtensionsBrowserTypeGuardTest : FunSpec({

    test("TSUNDOKU registers no installed-extension info map in the legacy browser") {
        val info = runBlocking {
            observeInstalledExtensionInfoMap(
                type = ExternalExtensionType.TSUNDOKU,
                mihonExtensionManager = mockk<MihonExtensionManager>(relaxed = true),
                aniyomiExtensionManager = mockk<AniyomiExtensionManager>(relaxed = true),
                ireaderExtensionManager = mockk<IReaderExtensionManager>(relaxed = true),
            ).first()
        }
        info shouldBe emptyMap<String, InstalledExtensionVersionInfo>()
    }

    test("TSUNDOKU registers no installed-extension entries in the legacy browser") {
        val entries = runBlocking {
            observeInstalledExtensionEntries(
                type = ExternalExtensionType.TSUNDOKU,
                mihonExtensionManager = mockk<MihonExtensionManager>(relaxed = true),
                aniyomiExtensionManager = mockk<AniyomiExtensionManager>(relaxed = true),
                ireaderExtensionManager = mockk<IReaderExtensionManager>(relaxed = true),
            ).first()
        }
        entries shouldBe emptyList<InstalledExtensionEntry>()
    }
})
