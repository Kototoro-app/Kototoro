package org.skepsun.kototoro.community.domain

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.io.File
import java.text.Normalizer
import java.util.Locale
import java.util.zip.ZipFile

/** The works of a real backup as the community evaluations see them; reads both the projection and the manga layouts. */
internal object CommunityEvalBackup {

    data class Work(
        val source: String,
        val url: String,
        val medium: CommunityMedium,
        val adult: Boolean,
        val titles: List<String>,
        val refs: List<CommunityExternalRef>,
    ) {
        val keys: List<String> = CommunityWorkKeys.keys(medium, titles, refs, fold)
        val titleKeys: List<String> get() = keys.filterNot(CommunityWorkKeys::isStrong)
    }

    /** What the JVM can do of the platform fold; Android adds traditional to simplified Chinese. */
    val fold: (String) -> String = { Normalizer.normalize(it, Normalizer.Form.NFKC).lowercase(Locale.ROOT) }

    private val scrobblers = listOf("", "SHIKIMORI", "ANILIST", "MAL", "KITSU", "BANGUMI", "MANGAUPDATES")

    fun read(file: File): List<Work> = ZipFile(file).use { zip ->
        fun section(name: String) = zip.getEntry(name)?.let { entry ->
            Json.parseToJsonElement(zip.getInputStream(entry).bufferedReader().readText()).jsonArray.map { it.jsonObject }
        }.orEmpty()
        fun JsonObject.text(key: String) = get(key)?.jsonPrimitive?.takeIf { it.isString }?.content
        val manga = section("projections").ifEmpty {
            (section("history") + section("favourites")).mapNotNull { it["manga"]?.jsonObject }
        }
        val scrobblings = section("scrobbling").groupBy { it["manga_id"]?.jsonPrimitive?.longOrNull }
        manga.distinctBy { it["id"]?.jsonPrimitive?.longOrNull }.map { item ->
            val type = item.text("content_type")
            val refs = buildList {
                val service = CommunityExternalService.of(item.text("metadata_source_service"))
                val remote = item.text("metadata_source_remote_id")?.toLongOrNull()
                if (service != null && remote != null) add(CommunityExternalRef(service, remote))
                scrobblings[item["id"]?.jsonPrimitive?.longOrNull].orEmpty().forEach { row ->
                    val scrobbler = row["scrobbler"]?.jsonPrimitive?.longOrNull?.toInt() ?: return@forEach
                    val target = row["target_id"]?.jsonPrimitive?.longOrNull ?: return@forEach
                    CommunityExternalService.of(scrobblers.getOrNull(scrobbler))?.let { add(CommunityExternalRef(it, target)) }
                }
            }
            Work(
                source = item.text("source").orEmpty(),
                url = item.text("url").orEmpty(),
                medium = CommunityMedium.of(type),
                adult = type?.startsWith("HENTAI_") == true || item["nsfw"]?.jsonPrimitive?.content == "true",
                titles = (listOfNotNull(item.text("title")) + item.text("alt_title").orEmpty().split('\n')).filter { it.isNotBlank() },
                refs = refs,
            )
        }
    }
}
