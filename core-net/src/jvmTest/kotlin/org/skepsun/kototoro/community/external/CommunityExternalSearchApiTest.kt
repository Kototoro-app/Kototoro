package org.skepsun.kototoro.community.external

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandler
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class CommunityExternalSearchApiTest {

    private suspend fun withApi(handler: MockRequestHandler, test: suspend (CommunityExternalSearchApi, MockEngine) -> Unit) {
        val engine = MockEngine(handler)
        val client = HttpClient(engine) { expectSuccess = true }
        try {
            test(CommunityExternalSearchApi(client), engine)
        } finally {
            client.close()
            engine.close()
        }
    }

    private val json = headersOf(HttpHeaders.ContentType, "application/json")

    @Test
    fun `anilist search posts the graphql query and lists every name of each hit`() = runTest {
        withApi({ request ->
            assertEquals(HttpMethod.Post, request.method)
            assertEquals("graphql.anilist.co", request.url.host)
            assertNull(request.headers[HttpHeaders.Authorization])
            val body = Json.parseToJsonElement((request.body as TextContent).text).jsonObject
            val variables = body.getValue("variables").jsonObject
            assertEquals("桃源暗鬼", variables.getValue("search").jsonPrimitive.content)
            assertEquals("MANGA", variables.getValue("type").jsonPrimitive.content)
            respond(
                """{"data":{"Page":{"media":[
                    {"id":119968,"format":"MANGA","isAdult":false,
                     "title":{"romaji":"Tougen Anki","english":null,"native":"桃源暗鬼"},"synonyms":["Tougen Anki",""]},
                    {"format":"MANGA"}
                ]}}}""",
                headers = json,
            )
        }) { api, _ ->
            val hits = api.search(CommunityExternalSite.ANILIST, "桃源暗鬼", CommunityExternalSearchKind.BOOK)
            assertEquals(
                listOf(CommunityExternalHit(CommunityExternalSite.ANILIST, 119968, "MANGA", listOf("Tougen Anki", "桃源暗鬼"), false)),
                hits,
            )
        }
    }

    @Test
    fun `anilist video search asks for anime`() = runTest {
        withApi({ request ->
            val body = Json.parseToJsonElement((request.body as TextContent).text).jsonObject
            assertEquals("ANIME", body.getValue("variables").jsonObject.getValue("type").jsonPrimitive.content)
            respond("""{"data":{"Page":{"media":[]}}}""", headers = json)
        }) { api, _ ->
            assertEquals(emptyList<CommunityExternalHit>(), api.search(CommunityExternalSite.ANILIST, "x", CommunityExternalSearchKind.VIDEO))
        }
    }

    @Test
    fun `bangumi search identifies itself, filters by type and reads aliases in both infobox shapes`() = runTest {
        withApi({ request ->
            assertEquals("api.bgm.tv", request.url.host)
            assertEquals("/v0/search/subjects", request.url.encodedPath)
            assertEquals(CommunityExternalSearchApi.DEFAULT_USER_AGENT, request.headers[HttpHeaders.UserAgent])
            val body = Json.parseToJsonElement((request.body as TextContent).text).jsonObject
            assertEquals("[2,6]", body.getValue("filter").jsonObject.getValue("type").toString())
            respond(
                """{"total":2,"data":[
                    {"id":11670,"type":2,"platform":"TV","nsfw":false,"name":"桃源の薬","name_cn":"桃源之药",
                     "infobox":[{"key":"别名","value":[{"v":"桃源之藥"},{"k":"en","v":"Peach"}]},{"key":"话数","value":"12"}]},
                    {"id":8216,"type":6,"platform":"","nsfw":true,"name":"A","name_cn":"",
                     "infobox":[{"key":"别名","value":"World Apartment Horror"}]}
                ]}""",
                headers = json,
            )
        }) { api, _ ->
            val hits = api.search(CommunityExternalSite.BANGUMI, "桃源", CommunityExternalSearchKind.VIDEO)
            assertEquals(
                listOf(
                    CommunityExternalHit(CommunityExternalSite.BANGUMI, 11670, "2/TV", listOf("桃源の薬", "桃源之药", "桃源之藥", "Peach"), false),
                    CommunityExternalHit(CommunityExternalSite.BANGUMI, 8216, "6/", listOf("A", "World Apartment Horror"), true),
                ),
                hits,
            )
        }
    }

    @Test
    fun `a refused search throws with the wait the site asked for, and a blank query asks nothing`() = runTest {
        withApi({
            respond("", HttpStatusCode.TooManyRequests, headersOf(HttpHeaders.RetryAfter, "60"))
        }) { api, engine ->
            val error = runCatching { api.search(CommunityExternalSite.ANILIST, "x", CommunityExternalSearchKind.BOOK) }
                .exceptionOrNull() as CommunityExternalSearchException
            assertEquals(429, error.status)
            assertEquals(60L, error.retryAfterSeconds)
            assertEquals(emptyList<CommunityExternalHit>(), api.search(CommunityExternalSite.BANGUMI, "  ", CommunityExternalSearchKind.BOOK))
            assertEquals(1, engine.requestHistory.size)
        }
    }
}
