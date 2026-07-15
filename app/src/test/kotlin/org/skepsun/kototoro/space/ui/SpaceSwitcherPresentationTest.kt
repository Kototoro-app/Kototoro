package org.skepsun.kototoro.space.ui

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.parsers.model.ContentType
import org.skepsun.kototoro.space.domain.BuiltInSpaces
import org.skepsun.kototoro.space.domain.SpaceContext
import org.skepsun.kototoro.space.domain.SpaceId
import org.skepsun.kototoro.space.domain.SpaceKind

class SpaceSwitcherPresentationTest {

    @Test
    fun `custom space uses first visible grapheme as monogram`() {
        customSpace("  小说").customMonogram() shouldBe "小"
        customSpace("Anime").customMonogram() shouldBe "A"
    }

    @Test
    fun `built in space keeps semantic icon`() {
        BuiltInSpaces.contexts.first().customMonogram() shouldBe null
    }

    private fun customSpace(title: String) = SpaceContext(
        id = SpaceId("custom:test"),
        kind = SpaceKind.MANGA,
        allowedContentTypes = setOf(ContentType.MANGA),
        title = title,
        isBuiltIn = false,
    )
}
