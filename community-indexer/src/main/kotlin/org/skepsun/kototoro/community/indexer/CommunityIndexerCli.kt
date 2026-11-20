package org.skepsun.kototoro.community.indexer

import io.ktor.client.HttpClient
import io.ktor.client.engine.ProxyBuilder
import io.ktor.client.engine.java.Java
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.http.Url
import kotlinx.coroutines.runBlocking
import org.skepsun.kototoro.community.nostr.KtorNostrRelayConnector
import org.skepsun.kototoro.community.nostr.NostrBech32
import org.skepsun.kototoro.community.nostr.NostrRelayClient
import java.io.File
import kotlin.system.exitProcess

private const val USAGE = """community-indexer --archive <dir> --out <dir> [options]
  --relays <a,b,…>       relays to read and to list in the manifest (default: plan §5 candidates)
  --maintainer <npub|hex> pubkey whose kind 10000 mute list moderates the index
  --pow-rating <bits>    proof of work a rating needs to count (default 16)
  --pow-comment <bits>   proof of work a comment needs to count (default 20)
  --full                 read relays from the beginning instead of two days before the newest archived event
  --offline              do not read relays; rebuild the snapshot from the archive alone
  --now <seconds>        clock for the snapshot (default: now)"""

// The app's own list (CommunityRepository.DEFAULT_RELAYS): two relays reachable from mainland China, then the major ones.
private val DEFAULT_RELAYS = listOf(
    "wss://yabu.me",
    "wss://relay.nostr.wirednet.jp",
    "wss://nos.lol",
    "wss://relay.primal.net",
    "wss://nostr.mom",
    "wss://relay.damus.io",
)

/**
 * One indexer run (plan §6.2): archive what relays have that the archive does not, then rebuild the snapshot from the
 * whole archive. Exits non-zero when no relay could be read, so a scheduled run that learned nothing fails visibly.
 */
fun main(args: Array<String>) {
    val options = parseOptions(args) ?: run {
        System.err.println(USAGE)
        exitProcess(2)
    }
    val archiveDir = File(options.getValue("archive"))
    val outDir = File(options.getValue("out"))
    val relays = options["relays"]?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() } ?: DEFAULT_RELAYS
    val maintainer = options["maintainer"]?.let { value ->
        if (value.startsWith(NostrBech32.PUBLIC_PREFIX)) NostrBech32.decode(value, NostrBech32.PUBLIC_PREFIX) else value.lowercase()
    }
    val config = IndexerConfig(
        now = options["now"]?.toLong() ?: (System.currentTimeMillis() / 1000),
        relays = relays,
        powRating = options["pow-rating"]?.toInt() ?: 16,
        powComment = options["pow-comment"]?.toInt() ?: 20,
        maintainerPubkey = maintainer,
    )

    val archived = IndexerArchive.load(archiveDir)
    println("archive: ${archived.size} events")
    var moderation = org.skepsun.kototoro.community.domain.CommunityModeration()
    var all = archived
    if ("offline" !in options) {
        val http = httpClient()
        try {
            val fetcher = RelayFetcher(NostrRelayClient(KtorNostrRelayConnector(http)))
            val since = if ("full" in options) null else archived.maxOfOrNull { it.event.createdAt }?.minus(2 * CommunityAggregator.DAY)
            val (events, reports) = runBlocking { fetcher.fetch(relays, since) }
            reports.forEach { println("  ${it.relay}: ${it.events} events in ${it.pages} pages${it.error?.let { e -> " ($e)" } ?: ""}") }
            if (reports.all { it.error != null }) {
                System.err.println("no relay could be read")
                exitProcess(1)
            }
            val added = IndexerArchive.append(archiveDir, archived, events.map { ArchivedEvent(it, config.now) })
            println("archived $added new events")
            moderation = runBlocking { fetcher.moderation(relays, maintainer) }
        } finally {
            http.close()
        }
        all = IndexerArchive.load(archiveDir)
    }

    val output = CommunityAggregator.aggregate(all, moderation, config)
    val manifest = SnapshotWriter.write(outDir, output, config)
    println("snapshot: ${manifest.stats}  files ${manifest.files.size}  key conflicts ${output.conflicts}  reported ${output.reports.size}")
}

private fun parseOptions(args: Array<String>): Map<String, String>? {
    val options = HashMap<String, String>()
    var i = 0
    while (i < args.size) {
        val name = args[i].removePrefix("--").takeIf { args[i].startsWith("--") } ?: return null
        if (name == "full" || name == "offline") {
            options[name] = "true"
            i++
        } else {
            options[name] = args.getOrNull(i + 1) ?: return null
            i += 2
        }
    }
    return options.takeIf { "archive" in it && "out" in it }
}

private fun httpClient(): HttpClient {
    val proxy = (System.getenv("https_proxy") ?: System.getenv("HTTPS_PROXY"))?.takeIf { it.startsWith("http") }
    return HttpClient(Java) {
        install(WebSockets)
        engine { if (proxy != null) this.proxy = ProxyBuilder.http(Url(proxy)) }
    }
}
