package org.skepsun.kototoro.settings

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class SourceSettingsTitleTest : FunSpec({

    test("route-provided source title wins over anonymous fallback title") {
        resolveSourceSettingsTitle("MangaDex", "Loading Mihon source...") shouldBe "MangaDex"
    }

    test("blank route title falls back to resolved source title") {
        resolveSourceSettingsTitle("  ", "Loading Mihon source...") shouldBe "Loading Mihon source..."
    }
})
