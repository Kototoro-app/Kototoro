package org.skepsun.kototoro.tracker.work

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.core.model.TestContentSource
import org.skepsun.kototoro.parsers.model.Content
import org.skepsun.kototoro.parsers.model.ContentChapter
import org.skepsun.kototoro.parsers.model.ContentRating
import org.skepsun.kototoro.parsers.model.ContentSource

class TrackerWebhookPayloadTest {

    @Test
    fun `a batch merges every work into one payload`() {
        val items = listOf(
            item("Alpha", chapters = 2),
            item("Beta", chapters = 1),
            item("Gamma", chapters = 3),
        )

        val payload = TrackerWebhookPayloads.buildBatch(items, timestamp = 1_700_000_000_000L)

        payload.event shouldBe TrackerWebhookPayloads.EVENT_CHAPTERS_UPDATED
        payload.timestamp shouldBe 1_700_000_000_000L
        payload.totalWorks shouldBe 3
        payload.totalChapters shouldBe 6
        payload.items.map { it.title } shouldBe listOf("Alpha", "Beta", "Gamma")
        payload.content shouldContain "3 works, 6 new chapters"
        payload.content shouldContain "Alpha — 2 new chapters"
    }

    @Test
    fun `a large batch is cut to twenty items while the totals stay complete`() {
        val items = (1..25).map { item("Work $it", chapters = 2) }

        val payload = TrackerWebhookPayloads.buildBatch(items, timestamp = 42L)

        payload.items.size shouldBe TrackerWebhookPayloads.MAX_ITEMS
        payload.items.size shouldBe 20
        payload.totalWorks shouldBe 25
        payload.totalChapters shouldBe 50
        payload.content shouldContain "25 works, 50 new chapters"
        payload.content shouldContain "(showing 20)"
        payload.content shouldNotContain "Work 21"
    }

    @Test
    fun `the payload serializes with the agreed field names`() {
        val payload = TrackerWebhookPayloads.buildBatch(
            listOf(item("Alpha", chapters = 1)),
            timestamp = 7L,
        )

        val json = Json.encodeToString(TrackerWebhookPayload.serializer(), payload)
        val root = Json.parseToJsonElement(json).jsonObject

        root["event"]?.jsonPrimitive?.content shouldBe "chapters_updated"
        root["timestamp"]?.jsonPrimitive?.content shouldBe "7"
        root["totalWorks"]?.jsonPrimitive?.content shouldBe "1"
        root["totalChapters"]?.jsonPrimitive?.content shouldBe "1"
        root["content"]?.jsonPrimitive?.content?.shouldNotBeNull()
        val items = root["items"]?.jsonArray.shouldNotBeNull()
        items.size shouldBe 1
        val first = items.single().jsonObject
        first["title"]?.jsonPrimitive?.content shouldBe "Alpha"
        first["source"]?.jsonPrimitive?.content shouldBe TestContentSource.name
        first["url"]?.jsonPrimitive?.content shouldBe "https://example.org/Alpha"
        first["newChaptersCount"]?.jsonPrimitive?.content shouldBe "1"
    }

    @Test
    fun `a test payload carries no work`() {
        val payload = TrackerWebhookPayloads.buildTest(timestamp = 5L)

        payload.event shouldBe TrackerWebhookPayloads.EVENT_TEST
        payload.totalWorks shouldBe 0
        payload.totalChapters shouldBe 0
        payload.items shouldBe emptyList()
        payload.content shouldContain "Kototoro webhook test"
    }

    @Test
    fun `an item carries the content title, source, public url and latest chapter`() {
        val content = content(title = "Alpha", publicUrl = "https://example.org/manga/alpha")

        val item = TrackerWebhookPayloads.buildItem(
            content = content,
            newChapters = chapters(count = 2, lastTitle = "Chapter 12"),
            excludeNsfw = false,
        ).shouldNotBeNull()

        item.title shouldBe "Alpha"
        item.source shouldBe TestContentSource.name
        item.url shouldBe "https://example.org/manga/alpha"
        item.newChaptersCount shouldBe 2
        item.latestChapterTitle shouldBe "Chapter 12"
    }

    @Test
    fun `a blank chapter title is not reported`() {
        val item = TrackerWebhookPayloads.buildItem(
            content = content(title = "Alpha"),
            newChapters = chapters(count = 1, lastTitle = " "),
            excludeNsfw = false,
        ).shouldNotBeNull()

        item.latestChapterTitle.shouldBeNull()
    }

    @Test
    fun `an nsfw work is left out when the tracker filters nsfw`() {
        val content = content(title = "Adult", rating = ContentRating.ADULT)

        TrackerWebhookPayloads.buildItem(content, chapters(count = 3), excludeNsfw = true).shouldBeNull()
    }

    @Test
    fun `an nsfw work is reported when the tracker does not filter nsfw`() {
        val content = content(title = "Adult", rating = ContentRating.ADULT)

        val item = TrackerWebhookPayloads.buildItem(content, chapters(count = 3), excludeNsfw = false)

        item?.title shouldBe "Adult"
    }

    @Test
    fun `a safe work is reported even when nsfw filtering is on`() {
        val content = content(title = "Safe", rating = ContentRating.SAFE)

        val item = TrackerWebhookPayloads.buildItem(content, chapters(count = 1), excludeNsfw = true)

        item?.title shouldBe "Safe"
    }

    @Test
    fun `a work without new chapters is not reported`() {
        TrackerWebhookPayloads.buildItem(
            content = content(title = "Alpha"),
            newChapters = emptyList(),
            excludeNsfw = false,
        ).shouldBeNull()
    }

    private fun item(title: String, chapters: Int) = TrackerWebhookItem(
        title = title,
        source = TestContentSource.name,
        url = "https://example.org/$title",
        newChaptersCount = chapters,
        latestChapterTitle = null,
    )

    private fun content(
        title: String,
        publicUrl: String = "https://example.org/$title",
        rating: ContentRating = ContentRating.SAFE,
        source: ContentSource = TestContentSource,
    ) = Content(
        id = title.hashCode().toLong(),
        title = title,
        altTitles = emptySet(),
        url = "/$title",
        publicUrl = publicUrl,
        rating = 0f,
        contentRating = rating,
        coverUrl = null,
        tags = emptySet(),
        state = null,
        authors = emptySet(),
        source = source,
    )

    private fun chapters(count: Int, lastTitle: String? = null) = (1..count).map { number ->
        ContentChapter(
            id = number.toLong(),
            title = if (number == count) lastTitle else "Chapter $number",
            number = number.toFloat(),
            volume = 0,
            url = "/chapter/$number",
            scanlator = null,
            uploadDate = 0L,
            branch = null,
            source = TestContentSource,
        )
    }
}
