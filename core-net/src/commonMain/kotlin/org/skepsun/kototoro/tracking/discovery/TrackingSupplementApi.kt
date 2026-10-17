package org.skepsun.kototoro.tracking.discovery

import io.ktor.client.HttpClient
import io.ktor.client.plugins.expectSuccess
import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.*

data class TrackingPerson(val id: String, val name: String, val image: String?, val url: String?, val role: String?,
    val voiceActors: List<TrackingPerson> = emptyList())
data class TrackingRelated(val item: TrackingMetadata, val relation: String)
data class TrackingSupplement(
    val characters: List<TrackingPerson> = emptyList(),
    val staff: List<TrackingPerson> = emptyList(),
    val related: List<TrackingRelated> = emptyList(),
    val recommendations: List<TrackingRelated> = emptyList(),
)

/** Optional detail sections load independently of the work's core metadata and local binding. */
internal class TrackingSupplementApi(
    private val client: HttpClient,
    private val clientIds: Map<Int, String>,
    private val catalog: (PublicTrackingSite, JsonObject, String) -> TrackingMetadata,
    private val bangumiBaseUrl: String,
) {
    suspend fun load(site: PublicTrackingSite, id: Long, kind: String): TrackingSupplement = when (site) {
        PublicTrackingSite.ANILIST -> anilist(id, kind)
        PublicTrackingSite.BANGUMI -> bangumi(id, kind)
        PublicTrackingSite.SHIKIMORI -> shikimori(id, kind)
        PublicTrackingSite.MAL -> mal(id, kind)
        PublicTrackingSite.KITSU -> kitsu(id, kind)
        else -> TrackingSupplement()
    }

    private suspend fun anilist(id: Long, kind: String): TrackingSupplement {
        val query = """query { Media(id: $id) {
            characters(perPage: 12) { edges { role node { id name { full } image { large } siteUrl }
                voiceActors(language: JAPANESE) { id name { full } image { large } siteUrl } } }
            staff(perPage: 12) { edges { role node { id name { full } image { large } siteUrl } } }
            relations { edges { relationType node { $RELATED_FIELDS } } }
            recommendations(perPage: 12, sort: RATING_DESC) { nodes { mediaRecommendation { $RELATED_FIELDS } } }
        } }"""
        val result = client.post("https://graphql.anilist.co") {
            expectSuccess = true
            jsonBody(buildJsonObject { put("query", query) })
        }.bodyAsText().let { Json.parseToJsonElement(it).jsonObject }
        check(result.arr("errors").isEmpty())
        val data = result.obj("data").obj("Media")
        fun person(j: JsonObject, role: String?) = TrackingPerson(j.str("id").orEmpty(), j.obj("name").str("full").orEmpty(),
            j.obj("image").str("large"), j.str("siteUrl"), role)
        fun work(j: JsonObject) = catalog(PublicTrackingSite.ANILIST, j, kind)
        return TrackingSupplement(
            characters = data.obj("characters").arr("edges").map { it.jsonObject }.map { edge ->
                person(edge.obj("node"), edge.str("role")).copy(voiceActors = edge.arr("voiceActors").map { person(it.jsonObject, "声优") })
            },
            staff = data.obj("staff").arr("edges").map { it.jsonObject }.map { person(it.obj("node"), it.str("role")) },
            related = data.obj("relations").arr("edges").map { it.jsonObject }.map { TrackingRelated(work(it.obj("node")), it.str("relationType").orEmpty()) },
            recommendations = data.obj("recommendations").arr("nodes").map { it.jsonObject.obj("mediaRecommendation") }
                .filter { it.number("id") != null }.map { TrackingRelated(work(it), "推荐") },
        )
    }

    private suspend fun bangumi(id: Long, kind: String): TrackingSupplement = coroutineScope {
        val base = bangumiBaseUrl.trimEnd('/')
        val characters = async { get("$base/v0/subjects/$id/characters").jsonArray }
        val staff = async { get("$base/v0/subjects/$id/persons").jsonArray }
        val related = async { get("$base/v0/subjects/$id/subjects").jsonArray }
        fun person(j: JsonObject, type: String) = TrackingPerson(j.str("id").orEmpty(), j.str("name").orEmpty(),
            j.obj("images").str("large") ?: j.obj("images").str("medium"), "https://bgm.tv/$type/${j.str("id")}", j.str("relation"))
        TrackingSupplement(
            characters = characters.await().map { it.jsonObject }.map { j ->
                person(j, "character").copy(voiceActors = j.arr("actors").map { person(it.jsonObject, "person") })
            },
            staff = staff.await().map { person(it.jsonObject, "person") },
            related = related.await().map { it.jsonObject }.filter { it.integer("type") in listOf(1, 2) }.map {
                TrackingRelated(catalog(PublicTrackingSite.BANGUMI, it, if (it.integer("type") == 2) "VIDEO" else "MANGA"), it.str("relation").orEmpty())
            },
        )
    }

    private suspend fun shikimori(id: Long, kind: String): TrackingSupplement = coroutineScope {
        val type = if (kind == "VIDEO") "animes" else "mangas"
        val roles = async { get("https://shikimori.io/api/$type/$id/roles").jsonArray }
        val works = async { get("https://shikimori.io/api/$type/$id/related").jsonArray }
        fun person(j: JsonObject, role: String?) = TrackingPerson(j.str("id").orEmpty(), j.str("russian") ?: j.str("name").orEmpty(),
            j.obj("image").str("original")?.let { if (it.startsWith('/')) "https://shikimori.io$it" else it },
            j.str("url")?.let { if (it.startsWith('/')) "https://shikimori.io$it" else it }, role)
        val people = roles.await().map { it.jsonObject }
        TrackingSupplement(
            characters = people.filter { it["character"] is JsonObject }.map {
                person(it.obj("character"), it.arr("roles").joinToString { role -> (role as? JsonPrimitive)?.contentOrNull.orEmpty() })
            },
            staff = people.filter { it["person"] is JsonObject }.map {
                person(it.obj("person"), it.arr("roles").joinToString { role -> (role as? JsonPrimitive)?.contentOrNull.orEmpty() })
            },
            related = works.await().map { it.jsonObject }.mapNotNull { edge ->
                val anime = edge["anime"] as? JsonObject
                val media = anime ?: edge["manga"] as? JsonObject ?: return@mapNotNull null
                TrackingRelated(catalog(PublicTrackingSite.SHIKIMORI, media, if (anime != null) "VIDEO" else "MANGA"), edge.str("relation").orEmpty())
            },
        )
    }

    private suspend fun mal(id: Long, kind: String): TrackingSupplement {
        val type = if (kind == "VIDEO") "anime" else "manga"
        val data = client.get("https://api.myanimelist.net/v2/$type/$id") {
            expectSuccess = true
            header("X-MAL-CLIENT-ID", clientIds[3].orEmpty())
            parameter("fields", "related_anime,related_manga,recommendations")
        }.bodyAsText().let { Json.parseToJsonElement(it).jsonObject }
        fun related(key: String, mediaKind: String) = data.arr(key).map { it.jsonObject }.map {
            TrackingRelated(catalog(PublicTrackingSite.MAL, it.obj("node"), mediaKind), it.str("relation_type_formatted").orEmpty())
        }
        return TrackingSupplement(related = related("related_anime", "VIDEO") + related("related_manga", "MANGA"),
            recommendations = related("recommendations", kind))
    }

    private suspend fun kitsu(id: Long, kind: String): TrackingSupplement {
        val type = if (kind == "VIDEO") "anime" else "manga"
        val data = get("https://kitsu.io/api/edge/$type/$id?include=mediaRelationships.destination").jsonObject
        val items = data.arr("included").map { it.jsonObject }.filter { it.str("type") in listOf("anime", "manga") }
        val relationships = data.arr("included").map { it.jsonObject }.filter { it.str("type") == "mediaRelationships" }
        return TrackingSupplement(related = relationships.mapNotNull { edge ->
            val target = edge.obj("relationships").obj("destination").obj("data")
            val media = items.firstOrNull { it.str("id") == target.str("id") && it.str("type") == target.str("type") }
                ?: return@mapNotNull null
            TrackingRelated(catalog(PublicTrackingSite.KITSU, media, if (media.str("type") == "anime") "VIDEO" else "MANGA"),
                edge.obj("attributes").str("role").orEmpty())
        })
    }

    private suspend fun get(url: String): JsonElement = client.get(url) { expectSuccess = true }.bodyAsText().let(Json::parseToJsonElement)

    companion object {
        private const val RELATED_FIELDS = "id type format title { userPreferred native } coverImage { large } siteUrl"
    }
}
