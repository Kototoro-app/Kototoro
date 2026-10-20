package org.skepsun.kototoro.tracking.discovery

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import io.ktor.http.content.TextContent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import kotlin.time.Instant

class PublicTrackingApiTest {
    @Test
    fun `anilist calendar passes local day boundaries drains pagination and deduplicates media`() = runTest {
        val day = TrackingScheduleDay(1792425600, 1792512000)
        var requests = 0
        withClient({ request ->
            requests++
            assertEquals("graphql.anilist.co", request.url.host)
            val query = Json.parseToJsonElement((request.body as TextContent).text).jsonObject["query"]!!.jsonPrimitive.content
            assertTrue(query.contains("airingAt_greater: ${day.start - 1}"))
            assertTrue(query.contains("airingAt_lesser: ${day.end}"))
            assertTrue(query.contains("page: $requests"))
            assertTrue(query.contains("sort: TIME"))
            val id = if (requests == 1) 21 else 22
            respond("""{"data":{"Page":{"pageInfo":{"hasNextPage":${requests == 1}},
                "airingSchedules":[{"media":{"id":$id,"type":"ANIME","title":{"romaji":"Scheduled $id"}}},
                {"media":{"id":21,"type":"ANIME","title":{"romaji":"Duplicate"}}}]}}}""")
        }) { client ->
            val api = PublicTrackingApi(client)
            val items = api.recommendations(PublicTrackingSite.ANILIST, "VIDEO", "calendar", 2, 1, day)
            assertEquals(listOf(21L, 22L), items.map { it.remoteId })
            assertTrue(items.all { it.service == 2 && it.mediaKind == "VIDEO" })
            assertTrue(api.recommendations(PublicTrackingSite.ANILIST, "VIDEO", "calendar", 2, 2, day).isEmpty())
            assertEquals(2, requests)
        }
    }

    @Test
    fun `shikimori calendar filters exact instants instead of weekday guesses`() = runTest {
        val day = TrackingScheduleDay(Instant.parse("2026-10-19T16:00:00Z").epochSeconds,
            Instant.parse("2026-10-20T16:00:00Z").epochSeconds)
        withClient({ request ->
            assertEquals("/api/calendar", request.url.encodedPath)
            respond("""[{"next_episode_at":"2026-10-19T15:59:59Z","anime":{"id":1,"name":"Before"}},
                {"next_episode_at":"2026-10-20T00:00:00+08:00","anime":{"id":2,"name":"Start"}},
                {"next_episode_at":"2026-10-20T10:00:00Z","anime":{"id":3,"name":"During"}},
                {"next_episode_at":"2026-10-20T18:00:00+09:00","anime":{"id":6,"name":"Earlier in another zone"}},
                {"next_episode_at":"2026-10-20T16:00:00Z","anime":{"id":4,"name":"Next day"}},
                {"next_episode_at":"unknown","anime":{"id":5,"name":"Unknown"}}]""")
        }) { client ->
            val items = PublicTrackingApi(client).recommendations(PublicTrackingSite.SHIKIMORI,
                "VIDEO", "calendar", 2, 1, day)
            assertEquals(listOf(2L, 6L, 3L), items.map { it.remoteId })
            assertTrue(items.all { it.service == 1 && it.mediaKind == "VIDEO" })
        }
    }

    @Test
    fun `simkl v2 calendar joins metadata across UTC months and retains TV identity`() = runTest {
        val day = TrackingScheduleDay(Instant.parse("2026-10-31T16:00:00Z").epochSeconds,
            Instant.parse("2026-11-01T16:00:00Z").epochSeconds)
        var requests = 0
        withClient({ request ->
            requests++
            assertEquals("data.simkl.in", request.url.host)
            assertEquals("test-client", request.url.parameters["client_id"])
            assertEquals("Kototoro", request.url.parameters["app-name"])
            val october = request.url.encodedPath.contains("/10/")
            assertEquals("/calendar/v2/2026/${if (october) 10 else 11}/tv.json", request.url.encodedPath)
            val date = if (october) "2026-10-31T17:00:00Z" else "2026-11-01T15:59:59Z"
            val id = if (october) 31 else 32
            respond("""{"calendar":[{"simkl_id":$id,"date":"$date"},
                {"simkl_id":$id,"date":"2026-11-01T16:00:00Z"}],
                "metadata":{"$id":{"title":"TV $id","ids":{"simkl_id":$id},
                "alt_titles":[{"name":"Alternate $id"}],"poster":"poster$id"}}}""")
        }) { client ->
            val items = PublicTrackingApi(client, mapOf(7 to "test-client")).recommendations(
                PublicTrackingSite.SIMKL, "VIDEO", "calendar_tv", 7, 1, day)
            assertEquals(listOf(31L, 32L), items.map { it.remoteId })
            assertTrue(items.all { it.remoteType == "tv" && it.url.contains("/tv/") })
            assertEquals(listOf("Alternate 31"), items.first().alternateTitles)
            assertEquals(2, requests)
        }
    }

    @Test
    fun `calendar capability does not advertise book releases or unsupported video schedules`() {
        for (site in PublicTrackingSite.entries) {
            assertFalse(site.supportsCalendar("MANGA"))
            assertFalse(site.supportsCalendar("NOVEL"))
        }
        assertFalse(PublicTrackingSite.KITSU.supportsCalendar("VIDEO"))
        assertFalse(PublicTrackingSite.MAL.supportsCalendar("VIDEO"))
        assertThrows(IllegalArgumentException::class.java) { TrackingScheduleDay(10, 10) }
        assertThrows(IllegalArgumentException::class.java) { TrackingScheduleDay(10, 10 + 27 * 3600) }
    }

    @Test
    fun `bangumi calendar respects mirror weekday finite paging and anime identity`() = runTest {
        var requests = 0
        withClient({ request ->
            requests++
            assertEquals("mirror.invalid", request.url.host)
            assertEquals("/api/calendar", request.url.encodedPath)
            respond("""[{"weekday":{"id":1},"items":[{"id":11,"type":2,"name":"Monday"}]},
                {"weekday":{"id":2},"items":[{"id":22,"type":2,"name_cn":"周二动画",
                "images":{"medium":"https://image.invalid/22.jpg"}},
                {"id":22,"type":2,"name":"duplicate"},{"id":23,"type":1,"name":"book"},
                {"id":0,"type":2,"name":"invalid"}]}]""")
        }) { client ->
            val api = PublicTrackingApi(client, bangumiBaseUrl = "https://mirror.invalid/api/")
            val items = api.recommendations(PublicTrackingSite.BANGUMI, "VIDEO", "calendar", 2, 1)
            assertEquals(listOf(22L), items.map { it.remoteId })
            assertEquals("周二动画", items.single().title)
            assertEquals("VIDEO", items.single().mediaKind)
            assertEquals("https://image.invalid/22.jpg", items.single().coverUrl)
            assertTrue(api.recommendations(PublicTrackingSite.BANGUMI, "VIDEO", "calendar", 2, 2).isEmpty())
            assertEquals(1, requests)
            assertThrows(IllegalArgumentException::class.java) {
                kotlinx.coroutines.runBlocking {
                    api.recommendations(PublicTrackingSite.BANGUMI, "MANGA", "calendar", 2, 1)
                }
            }
        }
    }

    @Test
    fun `recommendation spaces do not mix manga with novels`() = runTest {
        withClient({ request ->
            val filter = Json.parseToJsonElement((request.body as TextContent).text).jsonObject["filter"]!!.jsonObject
            assertEquals("小说", filter["tag"]!!.jsonArray.single().jsonPrimitive.content)
            respond("""{"data":[{"id":1,"type":1,"name":"小说"}]}""")
        }) { client ->
            assertEquals("NOVEL", PublicTrackingApi(client)
                .recommendations(PublicTrackingSite.BANGUMI, "NOVEL", "popular", 1, 1).single().mediaKind)
        }
        withClient({ respond("""{"data":{"Page":{"media":[
            {"id":1,"type":"MANGA","format":"NOVEL","title":{"native":"小说"}},
            {"id":2,"type":"MANGA","format":"MANGA","title":{"native":"漫画"}}]}}}""") }) { client ->
            val items = PublicTrackingApi(client).recommendations(PublicTrackingSite.ANILIST, "MANGA", "popular", 1, 1)
            assertEquals(listOf(2L), items.map { it.remoteId })
        }
    }

    @Test
    fun `bangumi preserves mirror query unicode media type and page offset`() = runTest {
        withClient({ request ->
            assertEquals("mirror.invalid", request.url.host)
            assertEquals("/api/v0/search/subjects", request.url.encodedPath)
            assertEquals("30", request.url.parameters["offset"])
            val body = Json.parseToJsonElement((request.body as TextContent).text).jsonObject
            assertEquals("葬送 + & \" ? #", body["keyword"]?.jsonPrimitive?.content)
            assertEquals(2, body["filter"]?.jsonObject?.get("type")?.jsonArray?.single()?.jsonPrimitive?.int)
            respond("""{"data":[]}""")
        }) { client ->
            BangumiDiscoveryApi(client).search("葬送 + & \" ? #", 30, true, "https://mirror.invalid/api/")
        }
    }

    @Test
    fun `bangumi details map full cover summary tags scores and infobox authors`() = runTest {
        withClient({ request ->
            assertEquals("/v0/subjects/42", request.url.encodedPath)
            respond("""{"id":42,"name":"Original","name_cn":"中文标题","type":2,"summary":"简介",
                "images":{"large":"https://image.invalid/full.jpg"},"rating":{"score":8.7},
                "date":"2024-01-01","eps":28,"tags":[{"name":"冒险"}],
                "infobox":[{"key":"导演","value":[{"v":"导演甲"}]}]}""")
        }) { client ->
            val item = PublicTrackingApi(client).details(PublicTrackingSite.BANGUMI, 42, "VIDEO")
            assertEquals("中文标题", item.title)
            assertEquals(listOf("Original"), item.alternateTitles)
            assertEquals("https://image.invalid/full.jpg", item.coverUrl)
            assertEquals("简介", item.description)
            assertEquals(8.7f, item.score)
            assertEquals(listOf("导演甲"), item.authors)
            assertEquals(2024, item.year)
            assertEquals(28, item.totalChapters)
            assertEquals("VIDEO", item.mediaKind)
        }
    }

    @Test
    fun `anilist novel searches encode quotes as data and retain page and format`() = runTest {
        withClient({ request ->
            assertEquals("graphql.anilist.co", request.url.host)
            val query = Json.parseToJsonElement((request.body as TextContent).text).jsonObject["query"]!!.jsonPrimitive.content
            assertTrue(query.contains("page: 3"))
            assertTrue(query.contains("type: MANGA"))
            assertTrue(query.contains("format: NOVEL"))
            assertTrue(query.contains(JsonPrimitive("a\"b\\c").toString()))
            respond("""{"data":{"Page":{"media":[{"id":12,"title":{"native":"小说"},"format":"NOVEL",
                "meanScore":82,"description":"简介","coverImage":{"large":"https://img.invalid/12"}}]}}}""")
        }) { client ->
            val item = PublicTrackingApi(client).search(PublicTrackingSite.ANILIST, "a\"b\\c", "NOVEL", 3).single()
            assertEquals("小说", item.title)
            assertEquals("NOVEL", item.mediaKind)
            assertEquals(100f, item.scoreMax)
        }
    }

    @Test
    fun `kitsu search encodes reserved text characters and uses exact media type for details`() = runTest {
        withClient({ request ->
            if (request.url.encodedPath.endsWith("/17")) {
                assertEquals("/api/edge/anime/17", request.url.encodedPath)
                respond("""{"data":{"id":"17","attributes":{"canonicalTitle":"动画","slug":"anime-slug",
                    "averageRating":"79.5","posterImage":{"original":"https://img.invalid/17"}}},"included":[]}""")
            } else {
                assertEquals("空 格 + & ? #", request.url.parameters["filter[text]"])
                assertEquals("20", request.url.parameters["page[offset]"])
                assertEquals("/api/edge/manga", request.url.encodedPath)
                respond("""{"data":[]}""")
            }
        }) { client ->
            val api = PublicTrackingApi(client)
            api.search(PublicTrackingSite.KITSU, "空 格 + & ? #", "MANGA", 2)
            val item = api.details(PublicTrackingSite.KITSU, 17, "VIDEO")
            assertEquals("https://kitsu.io/anime/anime-slug", item.url)
            assertEquals(79.5f, item.score)
        }
    }

    @Test
    fun `errors malformed identities and cancellation remain failures instead of empty success`() = runTest {
        withClient({ respond("""{"errors":[{"message":"rate limited"}],"data":null}""") }) { client ->
            assertThrows(IllegalStateException::class.java) {
                kotlinx.coroutines.runBlocking { PublicTrackingApi(client).search(PublicTrackingSite.ANILIST, "test", "MANGA") }
            }
        }
        withClient({ respond("""{"id":43,"name":"Wrong identity"}""") }) { client ->
            assertThrows(IllegalArgumentException::class.java) {
                kotlinx.coroutines.runBlocking { PublicTrackingApi(client).details(PublicTrackingSite.BANGUMI, 42, "MANGA") }
            }
        }
        withClient({ throw CancellationException("cancelled") }) { client ->
            assertThrows(CancellationException::class.java) {
                kotlinx.coroutines.runBlocking { PublicTrackingApi(client).search(PublicTrackingSite.KITSU, "test", "MANGA") }
            }
        }
        withClient({ respond("unavailable", HttpStatusCode.ServiceUnavailable) }) { client ->
            assertThrows(io.ktor.client.plugins.ServerResponseException::class.java) {
                kotlinx.coroutines.runBlocking { PublicTrackingApi(client).details(PublicTrackingSite.BANGUMI, 42, "MANGA") }
            }
        }
    }

    private suspend fun withClient(handler: MockRequestHandler, block: suspend (HttpClient) -> Unit) {
        val engine = MockEngine(handler)
        val client = HttpClient(engine)
        try { block(client) } finally { client.close(); engine.close() }
    }
}
