package org.skepsun.kototoro.core.parser

import eu.kanade.tachiyomi.source.Source
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.skepsun.kototoro.core.model.ContentSource
import org.skepsun.kototoro.core.model.UnknownContentSource
import org.skepsun.kototoro.tsundoku.TsundokuExtensionManager
import org.skepsun.kototoro.tsundoku.model.TsundokuNovelSource

/**
 * The browse flow serializes a source into its bare name (`TSUNDOKU_{id}`) in a `KEY_SOURCE`
 * intent extra and reconstructs an anonymous wrapper in `RemoteListViewModel`. That wrapper must
 * be rebuilt into the live [TsundokuNovelSource] before the repository provider registry can
 * select the Tsundoku provider — otherwise browsing falls through to
 * `EmptyContentRepository` and throws "This manga source is not supported".
 */
class TsundokuContentSourceResolverTest : FunSpec({

    val source = TsundokuNovelSource(
        upstreamSource = object : Source {
            override val id: Long = 42L
            override val name: String = "Example Novel"
            override val lang: String = "en"
        },
        pkgName = "org.tsundoku.test",
        isNsfw = false,
    )

    test("resolves an anonymous TSUNDOKU_ wrapper to the live TsundokuNovelSource") {
        val manager = mockk<TsundokuExtensionManager>(relaxed = true)
        every { manager.resolveSource("TSUNDOKU_42") } returns source

        val resolver = TsundokuContentSourceResolver(manager)
        val anonymous = ContentSource("TSUNDOKU_42")

        resolver.supports(anonymous) shouldBe true
        resolver.resolve(anonymous) shouldBe source
    }

    test("does not claim an already-resolved TsundokuNovelSource") {
        val resolver = TsundokuContentSourceResolver(mockk<TsundokuExtensionManager>(relaxed = true))

        resolver.supports(source) shouldBe false
        resolver.resolve(source) shouldBe null
    }

    test("does not claim unknown or unrelated sources") {
        val manager = mockk<TsundokuExtensionManager>(relaxed = true)
        every { manager.getTsundokuNovelSources() } returns emptyList()

        val resolver = TsundokuContentSourceResolver(manager)

        resolver.supports(UnknownContentSource) shouldBe false
        resolver.supports(ContentSource("MIHON_7")) shouldBe false
        resolver.supports(ContentSource("some.random.name")) shouldBe false
        resolver.resolve(ContentSource("some.random.name")) shouldBe null
    }

    test("resolves by plain display name when a single source matches") {
        val manager = mockk<TsundokuExtensionManager>(relaxed = true)
        every { manager.getTsundokuNovelSources() } returns listOf(source)

        val resolver = TsundokuContentSourceResolver(manager)

        resolver.supports(ContentSource("Example Novel")) shouldBe true
        resolver.resolve(ContentSource("Example Novel")) shouldBe source
    }
})
