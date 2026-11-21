package org.skepsun.kototoro.community.indexer

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.skepsun.kototoro.community.nostr.CommunityEventSchema
import org.skepsun.kototoro.community.nostr.KtorNostrRelayConnector
import org.skepsun.kototoro.community.nostr.NostrBech32
import org.skepsun.kototoro.community.nostr.NostrEvent
import org.skepsun.kototoro.community.nostr.NostrFilter
import org.skepsun.kototoro.community.nostr.NostrKeyPair
import org.skepsun.kototoro.community.nostr.NostrKeys
import org.skepsun.kototoro.community.nostr.NostrRelayClient
import org.skepsun.kototoro.community.nostr.NostrUnsignedEvent

internal const val MAINTAINER_USAGE = """community-indexer keygen
  prints a new maintainer key: the nsec on the first line, the npub on the second
community-indexer pubkey
  prints the npub of the key in KOTOTORO_MAINTAINER_NSEC
community-indexer mute show [--relays <a,b,…>]
community-indexer mute add|remove [--pubkey <npub|hex>]… [--event <note|hex>]… [--word <text>]… [--relays <a,b,…>]
  edits the maintainer's kind 10000 mute list (plan §4): the newest copy any relay has, changed, signed with
  KOTOTORO_MAINTAINER_NSEC and published to every relay. Tags this tool does not know are kept."""

/**
 * The maintainer's side of moderation (plan §6.2 step 6): the secret key never reaches CI, which only knows the npub
 * (`MAINTAINER_NPUB`); this runs on the maintainer's machine, where `deploy/maintainer.sh` keeps the key in the Keychain.
 */
internal object MaintainerCli {

    fun run(args: List<String>): Int = when (args.firstOrNull()) {
        "keygen" -> {
            val keys = NostrKeyPair.generate()
            println(NostrBech32.encodeSecretKey(keys))
            println(NostrBech32.encodePublicKey(keys.publicKeyHex))
            0
        }
        "pubkey" -> {
            println(NostrBech32.encodePublicKey(secret().publicKeyHex))
            0
        }
        "mute" -> mute(args.drop(1))
        else -> usage()
    }

    private fun mute(args: List<String>): Int {
        val action = args.firstOrNull() ?: return usage()
        val options = args.drop(1).chunked(2).map { pair ->
            if (pair.size != 2 || !pair[0].startsWith("--")) return usage()
            pair[0].removePrefix("--") to pair[1]
        }
        val relays = options.lastOrNull { it.first == "relays" }?.second?.split(',')?.map(String::trim)?.filter(String::isNotEmpty)
            ?: INDEXER_RELAYS
        val keys = secret()
        val http = indexerHttpClient()
        try {
            val client = NostrRelayClient(KtorNostrRelayConnector(http))
            val current = runBlocking { latest(client, relays, keys.publicKeyHex) }
            val tags = current?.tags.orEmpty()
            if (action == "show") {
                println(current?.let { "mute list of ${it.createdAt}, ${tags.size} entries" } ?: "no mute list on these relays")
                tags.forEach { println("  ${it.joinToString(" ")}") }
                return 0
            }
            if (action != "add" && action != "remove") return usage()
            val changes = options.filter { it.first != "relays" }.map { (name, value) ->
                when (name) {
                    "pubkey" -> listOf("p", decode(value, NostrBech32.PUBLIC_PREFIX))
                    "event" -> listOf("e", decode(value, "note"))
                    "word" -> listOf("word", value.trim().lowercase().also { require(it.isNotEmpty()) { "empty word" } })
                    else -> return usage()
                }
            }
            if (changes.isEmpty()) return usage()
            val next = if (action == "add") tags + changes.filter { it !in tags } else tags.filter { it !in changes }
            if (next == tags) {
                println("nothing to change")
                return 0
            }
            val now = System.currentTimeMillis() / 1000
            val event = NostrKeys.sign(NostrUnsignedEvent(
                createdAt = maxOf(now, (current?.createdAt ?: 0) + 1),
                kind = CommunityEventSchema.KIND_MUTE_LIST,
                tags = next,
                content = current?.content.orEmpty(),
            ), keys)
            val results = runBlocking {
                coroutineScope { relays.map { relay -> async { client.publish(relay, event) } }.awaitAll() }
            }
            results.forEach { println("  ${it.relay}: ${if (it.accepted) "accepted" else "refused"} ${it.message}") }
            val accepted = results.count { it.accepted }
            println("published ${event.id} to $accepted of ${relays.size} relays; the next index run applies it")
            return if (accepted > 0) 0 else 1
        } finally {
            http.close()
        }
    }

    /** The newest mute list of [pubkey] on any of [relays]. */
    private suspend fun latest(client: NostrRelayClient, relays: List<String>, pubkey: String): NostrEvent? {
        val filter = NostrFilter(authors = listOf(pubkey), kinds = listOf(CommunityEventSchema.KIND_MUTE_LIST), limit = 1)
        return coroutineScope {
            relays.map { relay -> async { client.query(relay, listOf(filter)).events } }.awaitAll()
        }.flatten().maxByOrNull { it.createdAt }
    }

    private fun secret(): NostrKeyPair {
        val value = System.getenv("KOTOTORO_MAINTAINER_NSEC")?.trim().orEmpty()
        require(value.isNotEmpty()) { "KOTOTORO_MAINTAINER_NSEC is not set" }
        val hex = if (value.startsWith(NostrBech32.SECRET_PREFIX)) NostrBech32.decode(value, NostrBech32.SECRET_PREFIX) else value
        return requireNotNull(hex?.let(NostrKeyPair::fromSecretHex)) { "KOTOTORO_MAINTAINER_NSEC is not a secret key" }
    }

    private fun decode(value: String, prefix: String): String {
        val hex = if (value.startsWith(prefix + "1")) NostrBech32.decode(value, prefix) else value.lowercase()
        require(hex != null && hex.length == 64 && hex.all { it in '0'..'9' || it in 'a'..'f' }) { "not a $prefix or hex id: $value" }
        return hex
    }

    private fun usage(): Int {
        System.err.println(MAINTAINER_USAGE)
        return 2
    }
}
