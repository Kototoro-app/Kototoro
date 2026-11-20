package org.skepsun.kototoro.community.indexer

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.skepsun.kototoro.community.domain.CommunitySnapshotManifest
import org.skepsun.kototoro.community.domain.CommunitySnapshotShards
import org.skepsun.kototoro.community.domain.CommunityWorkSummary
import java.io.File
import java.security.MessageDigest

/** Writes [IndexOutput] as the snapshot layout in CommunitySnapshot.kt, replacing whatever `v1/` held before. */
object SnapshotWriter {

    private val json = Json { encodeDefaults = true }

    fun write(out: File, output: IndexOutput, config: IndexerConfig): CommunitySnapshotManifest {
        File(out, "v1").deleteRecursively()
        val files = sortedMapOf<String, String>()
        fun put(path: String, text: String) {
            val file = File(out, path)
            file.parentFile.mkdirs()
            file.writeText(text)
            files[path] = sha256(text)
        }

        output.keys.entries.groupBy { CommunitySnapshotShards.of(it.key) }.forEach { (shard, entries) ->
            val sorted: Map<String, String> = entries.sortedBy { it.key }.associate { it.key to it.value }
            put(CommunitySnapshotShards.keysPath(shard), json.encodeToString(sorted))
        }
        output.works.values.groupBy { CommunitySnapshotShards.of(it.id) }.forEach { (shard, works) ->
            val sorted: Map<String, CommunityWorkSummary> = works.sortedBy { it.id }.associateBy { it.id }
            put(CommunitySnapshotShards.worksPath(shard), json.encodeToString(sorted))
        }
        output.charts.forEach { chart ->
            put(CommunitySnapshotShards.chartPath(chart.window, chart.medium, chart.nsfw), json.encodeToString(chart))
        }
        put(CommunitySnapshotShards.MODERATION_PATH, json.encodeToString(output.moderation))

        val manifest = CommunitySnapshotManifest(
            generatedAt = config.now,
            relays = config.relays,
            powRating = config.powRating,
            powComment = config.powComment,
            maintainerPubkey = config.maintainerPubkey,
            minChartWeight = config.minChartWeight,
            files = files,
            stats = output.stats,
        )
        File(out, CommunitySnapshotShards.MANIFEST_PATH).writeText(json.encodeToString(manifest))
        return manifest
    }

    private fun sha256(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.encodeToByteArray()).joinToString("") { "%02x".format(it) }
}
