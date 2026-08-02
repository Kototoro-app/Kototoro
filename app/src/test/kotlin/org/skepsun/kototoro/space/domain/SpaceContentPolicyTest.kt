package org.skepsun.kototoro.space.domain

import io.mockk.mockk
import io.mockk.every
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.parsers.model.ContentType
import org.skepsun.kototoro.explore.data.SourceRuleResolver
import org.skepsun.kototoro.explore.data.ContentSourcesRepository
import org.skepsun.kototoro.core.jsonsource.SourceType
import org.skepsun.kototoro.core.jsonsource.SourceTypeIdentifier
import org.skepsun.kototoro.core.model.ContentSourceInfo
import org.skepsun.kototoro.parsers.model.ContentSource
import org.skepsun.kototoro.space.data.TestSpaceCatalogRepository

class SpaceContentPolicyTest {

    private val policy = DefaultSpaceContentPolicy(
        TestSpaceCatalogRepository(),
        mockk<SourceRuleResolver>(relaxed = true),
    )

    @Test
    fun `built in spaces use stable ids`() {
        assertEquals("builtin:manga", BuiltInSpaces.Manga.value)
        assertEquals("builtin:novel", BuiltInSpaces.Novel.value)
        assertEquals("builtin:anime", BuiltInSpaces.Anime.value)
    }

    @Test
    fun `manga space contains every image based content type`() {
        assertEquals(
            setOf(
                ContentType.MANGA,
                ContentType.MANHWA,
                ContentType.MANHUA,
                ContentType.COMICS,
                ContentType.HENTAI_MANGA,
                ContentType.ONE_SHOT,
                ContentType.DOUJINSHI,
                ContentType.IMAGE_SET,
                ContentType.ARTIST_CG,
                ContentType.GAME_CG,
            ),
            policy.allowedTypes(BuiltInSpaces.Manga),
        )
    }

    @Test
    fun `novel and anime spaces contain their explicit content types`() {
        assertEquals(
            setOf(ContentType.NOVEL, ContentType.HENTAI_NOVEL),
            policy.allowedTypes(BuiltInSpaces.Novel),
        )
        assertEquals(
            setOf(ContentType.VIDEO, ContentType.HENTAI_VIDEO),
            policy.allowedTypes(BuiltInSpaces.Anime),
        )
    }

    @Test
    fun `every classified content type belongs to exactly one space`() {
        val occurrences = BuiltInSpaces.contexts
            .flatMap(SpaceContext::allowedContentTypes)
            .groupingBy { it }
            .eachCount()

        assertEquals(ContentType.entries.toSet() - ContentType.OTHER, occurrences.keys)
        assertTrue(occurrences.values.all { it == 1 })
    }

    @Test
    fun `other and unresolved content are not assigned to a space`() {
        assertNull(policy.spaceFor(ContentType.OTHER))
        assertNull(policy.spaceFor(null))
        BuiltInSpaces.contexts.forEach { context ->
            assertFalse(policy.accepts(context.id, ContentType.OTHER))
            assertFalse(policy.accepts(context.id, null))
        }
    }

    @Test
    fun `content lookup and acceptance use the same mapping`() {
        BuiltInSpaces.contexts.forEach { context ->
            context.allowedContentTypes.forEach { contentType ->
                assertEquals(context.id, policy.spaceFor(contentType))
                assertTrue(policy.accepts(context.id, contentType))
            }
        }
    }

    @Test
    fun `unknown space has no allowed content types`() {
        val unknown = SpaceId("custom:unknown")

        assertEquals(emptySet<ContentType>(), policy.allowedTypes(unknown))
        assertFalse(policy.accepts(unknown, ContentType.MANGA))
    }

    @Test
    fun `source scope refreshes when mihon extension loads`() = runTest {
        val spaceId = SpaceId("custom:mihon")
        val catalog = TestSpaceCatalogRepository(
            listOf(
                SpaceContext(
                    id = spaceId,
                    kind = SpaceKind.MANGA,
                    allowedContentTypes = setOf(ContentType.MANGA),
                    sourceLanguages = setOf("en"),
                    sourceKinds = setOf(SourceType.MIHON),
                    isBuiltIn = false,
                ),
            ),
        )
        val sources = MutableStateFlow(emptyList<ContentSourceInfo>())
        val sourceRepository = mockk<ContentSourcesRepository> {
            every { observeEnabledSources() } returns sources
        }
        val dynamicPolicy = DefaultSpaceContentPolicy(
            catalog,
            SourceRuleResolver(sourceRepository, SourceTypeIdentifier()),
        )
        val observed = dynamicPolicy.observeAllowedSourceNames(spaceId)

        assertEquals(emptySet<String>(), observed.first())
        val mihon = mockk<ContentSource> {
            every { name } returns "MIHON_123"
            every { locale } returns "en"
            every { contentType } returns ContentType.MANGA
        }
        sources.value = listOf(ContentSourceInfo(mihon, isEnabled = true, isPinned = false))

        assertEquals(setOf("MIHON_123"), observed.first { it?.isNotEmpty() == true })
    }
}
