package org.skepsun.kototoro.extensions.repo

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class JarExtensionLanguageInspectorTest {

    @Test
    fun `extracts languages from Kototoro Kotatsu and Tsuki class names`() {
        val classNames = sequenceOf(
            "org.skepsun.kototoro.parsers.site.zh.ManhuaguiParser",
            "org.koitharu.kotatsu.parsers.site.en.MangaDexParser",
            "tsuki.site.ar.MangaArabia",
            "tsuki.site.pt_br.SomeParser",
        )

        JarExtensionLanguageInspector.fromClassNames(classNames) shouldBe JarExtensionLanguageMetadata(
            languageCodes = setOf("ar", "en", "pt-br", "zh"),
            includesUniversalLanguage = false,
            isKnown = true,
        )
    }

    @Test
    fun `ignores universal and unrelated classes`() {
        val classNames = sequenceOf(
            "tsuki.site.all.MangaReader",
            "tsuki.site.all.nsfw.HitomiLaParser",
            "tsuki.model.MangaSource",
            "org.example.site.ko.Parser",
        )

        JarExtensionLanguageInspector.fromClassNames(classNames) shouldBe JarExtensionLanguageMetadata(
            languageCodes = emptySet(),
            includesUniversalLanguage = true,
            isKnown = true,
        )
    }

    @Test
    fun `unrecognized jar layout keeps language metadata unknown`() {
        JarExtensionLanguageInspector.fromClassNames(
            sequenceOf("org.example.site.ko.Parser"),
        ) shouldBe JarExtensionLanguageMetadata(
            languageCodes = emptySet(),
            includesUniversalLanguage = false,
            isKnown = false,
        )
    }
}
