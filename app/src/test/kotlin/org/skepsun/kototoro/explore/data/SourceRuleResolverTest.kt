package org.skepsun.kototoro.explore.data

import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.core.jsonsource.SourceType
import org.skepsun.kototoro.core.jsonsource.SourceTypeIdentifier
import org.skepsun.kototoro.core.model.ContentSourceInfo
import org.skepsun.kototoro.parsers.model.ContentSource
import org.skepsun.kototoro.parsers.model.ContentType

class SourceRuleResolverTest {

    @Test
    fun `dimensions use and while values within one dimension use or`() {
        val resolver = resolverWith(MutableStateFlow(emptyList()))
        val sources = listOf(
            source("MIHON_EN", "en", ContentType.MANGA),
            source("MIHON_JA", "ja", ContentType.MANGA),
            source("ANIYOMI_EN", "en", ContentType.VIDEO),
        )

        resolver.resolveSourceNames(
            SourceRule(
                languages = setOf("en", "ja"),
                contentTypes = setOf(ContentType.MANGA),
                sourceTypes = setOf(SourceType.MIHON),
            ),
            sources,
        ).shouldContainExactlyInAnyOrder("MIHON_EN", "MIHON_JA")
    }

    @Test
    fun `registry emission refreshes language result`() = runTest {
        val sources = MutableStateFlow(listOf(source("MIHON_EN", "en", ContentType.MANGA)))
        val resolver = resolverWith(sources)
        val observed = resolver.observeResolvedSourceNames(SourceRule(languages = setOf("en")))

        observed.first().shouldContainExactlyInAnyOrder("MIHON_EN")
        sources.value = sources.value + source("ANIYOMI_EN", "en", ContentType.VIDEO)

        observed.first { it.size == 2 }.shouldContainExactlyInAnyOrder("MIHON_EN", "ANIYOMI_EN")
    }

    private fun resolverWith(sources: MutableStateFlow<List<ContentSourceInfo>>): SourceRuleResolver {
        val repository = mockk<ContentSourcesRepository>()
        every { repository.observeEnabledSources() } returns sources
        return SourceRuleResolver(repository, SourceTypeIdentifier())
    }

    private fun source(name: String, language: String, type: ContentType): ContentSourceInfo {
        val source = mockk<ContentSource>()
        every { source.name } returns name
        every { source.locale } returns language
        every { source.contentType } returns type
        return ContentSourceInfo(source, isEnabled = true, isPinned = false)
    }
}
