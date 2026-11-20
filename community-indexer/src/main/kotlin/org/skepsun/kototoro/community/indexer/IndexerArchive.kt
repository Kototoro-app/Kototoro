package org.skepsun.kototoro.community.indexer

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import org.skepsun.kototoro.community.nostr.NostrEvent
import org.skepsun.kototoro.community.nostr.NostrKeys
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.time.ZoneOffset
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * The durable copy of every Kototoro event (plan §6.1): `events/<yyyy-MM>.jsonl.gz` by the month the indexer first saw
 * each event, one `{"seen":…,"event":{…}}` per line. Relays may drop old events; this does not, and it can republish them.
 */
object IndexerArchive {

    private val json = Json { ignoreUnknownKeys = true }

    fun load(dir: File): List<ArchivedEvent> {
        val files = File(dir, EVENTS).listFiles { file -> file.name.endsWith(".jsonl.gz") }.orEmpty().sortedBy { it.name }
        val events = LinkedHashMap<String, ArchivedEvent>()
        for (file in files) {
            GZIPInputStream(file.inputStream()).bufferedReader().useLines { lines ->
                lines.filter { it.isNotBlank() }.forEach { line ->
                    val root = json.parseToJsonElement(line).jsonObject
                    val event = NostrEvent.fromJson(root.getValue("event").jsonObject) ?: return@forEach
                    // Checked again on load: the archive is a public branch anyone could send a pull request to.
                    if (!NostrKeys.verify(event)) return@forEach
                    val archived = ArchivedEvent(event, root.getValue("seen").jsonPrimitive.long)
                    events.merge(event.id, archived) { old, new -> if (new.seen < old.seen) new else old }
                }
            }
        }
        return events.values.toList()
    }

    /** Adds [events] not already archived, rewriting only the months they land in; returns how many were new. */
    fun append(dir: File, existing: Collection<ArchivedEvent>, events: Collection<ArchivedEvent>): Int {
        val known = existing.mapTo(HashSet()) { it.event.id }
        val added = events.filter { known.add(it.event.id) }
        if (added.isEmpty()) return 0
        val byMonth = existing.groupBy { monthOf(it.seen) }
        for ((month, list) in added.groupBy { monthOf(it.seen) }) {
            val all = (byMonth[month].orEmpty() + list).sortedWith(compareBy<ArchivedEvent> { it.seen }.thenBy { it.event.id })
            val target = File(File(dir, EVENTS).apply { mkdirs() }, "$month.jsonl.gz")
            val temp = File(target.parentFile, ".${target.name}.part")
            GZIPOutputStream(temp.outputStream()).bufferedWriter().use { writer ->
                all.forEach { writer.append(line(it).toString()).append('\n') }
            }
            Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        }
        return added.size
    }

    private fun line(event: ArchivedEvent): JsonObject = buildJsonObject {
        put("seen", event.seen)
        put("event", event.event.toJson())
    }

    private fun monthOf(seconds: Long): String =
        Instant.ofEpochSecond(seconds).atOffset(ZoneOffset.UTC).let { "%04d-%02d".format(it.year, it.monthValue) }

    private const val EVENTS = "events"
}
