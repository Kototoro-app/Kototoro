package org.skepsun.kototoro.space.domain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.parsers.model.ContentType

class SpaceContentPolicyTest {

    private val policy = DefaultSpaceContentPolicy()

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
}
