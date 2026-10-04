package org.skepsun.kototoro.core.source

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.parsers.model.Content
import org.skepsun.kototoro.parsers.model.ContentChapter
import org.skepsun.kototoro.parsers.model.ContentExternalTrack
import org.skepsun.kototoro.parsers.model.ContentListFilter
import org.skepsun.kototoro.parsers.model.ContentListFilterOptions
import org.skepsun.kototoro.parsers.model.ContentPage
import org.skepsun.kototoro.parsers.model.ContentRating
import org.skepsun.kototoro.parsers.model.ContentSource
import org.skepsun.kototoro.parsers.model.ContentState
import org.skepsun.kototoro.parsers.model.ContentTag
import org.skepsun.kototoro.parsers.model.ContentTagGroup
import org.skepsun.kototoro.parsers.model.ContentType
import org.skepsun.kototoro.parsers.model.Demographic
import org.skepsun.kototoro.parsers.model.EbookFormat
import java.util.Locale

internal val protocolTestSource = object : ContentSource {
    override val name = "MIHON_9007199254740993"
    override val locale = "zh"
    override val contentType = ContentType.MANGA
}

internal fun protocolTestChapter() = ContentChapter(
    id = Long.MIN_VALUE, title = null, number = 1.5f, volume = 2, url = "/chapter#memo", scanlator = "汉化",
    uploadDate = Long.MAX_VALUE, branch = "繁體", source = protocolTestSource, sourceData = "{\"memo\":\"原样\"}",
    ebookFormats = listOf(EbookFormat.EPUB, EbookFormat.CBZ),
)

internal fun protocolTestContent() = Content(
    id = Long.MAX_VALUE, title = "漫画 📚", altTitles = linkedSetOf("別名", "Alias"), url = "/manga",
    publicUrl = "https://fixture.test/manga", rating = -1f, contentRating = ContentRating.SUGGESTIVE,
    coverUrl = null, tags = setOf(ContentTag("分类", "key", protocolTestSource)), state = ContentState.PAUSED,
    authors = linkedSetOf("作者一", "作者二"), largeCoverUrl = "https://fixture.test/cover",
    description = "<p>HTML</p>", chapters = listOf(protocolTestChapter()), source = protocolTestSource,
    sourceData = "{\"memo\":{\"id\":123}}",
)

internal fun protocolTestPage() = ContentPage(
    id = -9007199254740993L, url = "/page#scramble-data", preview = "https://fixture.test/preview",
    headers = linkedMapOf("Referer" to "https://fixture.test", "X-Context" to "原样"),
    externalSubtitleTracks = listOf(ContentExternalTrack("https://fixture.test/sub", "zh", emptyMap())),
    playbackLabel = "线路一", playbackQuality = 1080, source = protocolTestSource,
)

class SourceModelMappingTest {
    @Test
    fun `Android rejects unsupported dynamic changes instead of discarding them during parser projection`() {
        assertThrows(SourceOperationUnsupportedException::class.java) {
            SourceFilter(dynamicFilters = listOf(SourceFilterChange("control", SourceFilterValue.Toggle(true))))
                .toParser { protocolTestSource }
        }
    }
    private val resolve: (String) -> ContentSource = { name ->
        assertEquals(protocolTestSource.name, name)
        protocolTestSource
    }

    @Test
    fun `content and nested chapters retain every field through JSON and parser mapping`() {
        val original = protocolTestContent()
        val dto = SourceProtocolJson.decodeFromString<SourceContent>(
            SourceProtocolJson.encodeToString(original.toSourceContent()),
        )
        val restored = dto.toParser(resolve)
        assertEquals(original, restored)
        assertSame(protocolTestSource, restored.source)
        assertSame(protocolTestSource, restored.chapters!!.single().source)
        assertSame(protocolTestSource, restored.tags.single().source)
    }

    @Test
    fun `page headers fragment subtitle and playback metadata survive round trip`() {
        val original = protocolTestPage()
        val dto = SourceProtocolJson.decodeFromString<SourcePage>(
            SourceProtocolJson.encodeToString(original.toSourcePage()),
        )
        assertEquals(original, dto.toParser(resolve))
    }

    @Test
    fun `nullable and loaded empty fields stay distinct at adapter boundary`() {
        listOf<List<ContentChapter>?>(null, emptyList()).forEach { chapters ->
            val original = protocolTestContent().copy(chapters = chapters, sourceData = "", contentRating = null)
            assertEquals(original, original.toSourceContent().toParser(resolve))
        }
        listOf<Map<String, String>?>(null, emptyMap()).forEach { headers ->
            val original = protocolTestPage().copy(headers = headers, preview = null)
            assertEquals(original, original.toSourcePage().toParser(resolve))
        }
    }

    @Test
    fun `filter converts enum names and BCP47 tags while retaining all fields`() {
        val tag = protocolTestContent().tags.single()
        val filter = SourceFilter(
            query = "中文 + &", tags = setOf(tag.toSourceTag()), tagsExclude = setOf(tag.toSourceTag()),
            locale = "zh-Hant-TW", originalLocale = "ja-JP", states = setOf("PAUSED"),
            contentRating = setOf("SUGGESTIVE"), types = setOf("MANGA"), demographics = setOf("SEINEN"),
            year = 2022, yearFrom = 2020, yearTo = 2026, author = "作者",
        )
        assertEquals(
            ContentListFilter(
                query = filter.query, tags = setOf(tag), tagsExclude = setOf(tag),
                locale = Locale.forLanguageTag("zh-Hant-TW"), originalLocale = Locale.JAPAN,
                states = setOf(ContentState.PAUSED), contentRating = setOf(ContentRating.SUGGESTIVE),
                types = setOf(ContentType.MANGA), demographics = setOf(Demographic.SEINEN),
                year = 2022, yearFrom = 2020, yearTo = 2026, author = "作者",
            ), filter.toParser(resolve),
        )
        assertEquals(ContentListFilter.EMPTY, SourceFilter().toParser(resolve))
    }

    @Test
    fun `filter options preserve ordered groups exclusivity and locale scripts`() {
        val tags = protocolTestContent().tags
        val options = ContentListFilterOptions(
            availableTags = tags,
            tagGroups = listOf(ContentTagGroup("分组", tags, true)),
            availableStates = setOf(ContentState.PAUSED),
            availableContentRating = setOf(ContentRating.SUGGESTIVE),
            availableContentTypes = setOf(ContentType.MANGA),
            availableDemographics = setOf(Demographic.SEINEN),
            availableLocales = setOf(Locale.forLanguageTag("zh-Hant-TW")),
        )
        assertEquals(
            SourceFilterOptions(
                tags.mapTo(linkedSetOf()) { it.toSourceTag() },
                listOf(SourceTagGroup("分组", tags.mapTo(linkedSetOf()) { it.toSourceTag() }, true)),
                setOf("PAUSED"), setOf("SUGGESTIVE"), setOf("MANGA"), setOf("SEINEN"), setOf("zh-Hant-TW"),
            ), options.toSourceFilterOptions(),
        )
    }

    @Test
    fun `explicit effective tag groups are preserved rather than recomputed`() {
        val options = ContentListFilterOptions(
            availableTags = protocolTestContent().tags,
            effectiveTagGroups = listOf(ContentTagGroup("自定义分组", emptySet(), true)),
        )
        assertEquals(
            listOf(SourceTagGroup("自定义分组", emptySet(), true)),
            options.toSourceFilterOptions().effectiveTagGroups,
        )
    }

    @Test
    fun `unknown enum values are explicit invalid arguments rather than silent defaults`() {
        assertThrows(SourceInvalidArgumentException::class.java) {
            SourceFilter(states = setOf("UNKNOWN_FUTURE_STATE")).toParser(resolve)
        }
        assertThrows(SourceInvalidArgumentException::class.java) {
            protocolTestContent().toSourceContent().copy(contentRating = "INVALID").toParser(resolve)
        }
        assertThrows(SourceInvalidArgumentException::class.java) {
            protocolTestChapter().toSourceChapter().copy(ebookFormats = listOf("INVALID")).toParser(resolve)
        }
    }

    @Test
    fun `Android page projection rejects host context that its parser ABI cannot preserve`() {
        assertThrows(org.skepsun.kototoro.core.source.SourceOperationUnsupportedException::class.java) {
            protocolTestPage().toSourcePage().copy(requestContext =
                org.skepsun.kototoro.core.source.SourcePageRequestContext(12, "/page", "/image#key"))
                .toParser(resolve)
        }
    }
}
