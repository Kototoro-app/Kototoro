package org.skepsun.kototoro.community.domain

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.skepsun.kototoro.community.data.CommunityKeyStore
import org.skepsun.kototoro.community.data.CommunityOutboxEntity
import org.skepsun.kototoro.community.data.CommunityRatingEntity
import org.skepsun.kototoro.community.data.CommunityResolutionEntity
import org.skepsun.kototoro.community.data.CommunityOutboxWorker
import org.skepsun.kototoro.community.data.CommunitySnapshotRepository
import org.skepsun.kototoro.community.external.CommunityExternalHit
import org.skepsun.kototoro.community.external.CommunityExternalSearchApi
import org.skepsun.kototoro.community.external.CommunityExternalSearchKind
import org.skepsun.kototoro.community.external.CommunityExternalSite
import org.skepsun.kototoro.community.nostr.CommunityEventSchema
import org.skepsun.kototoro.community.nostr.NostrEvent
import org.skepsun.kototoro.community.nostr.NostrKeyPair
import org.skepsun.kototoro.community.nostr.NostrKeys
import org.skepsun.kototoro.community.nostr.NostrPow
import org.skepsun.kototoro.community.nostr.NostrRelayClient
import org.skepsun.kototoro.community.nostr.NostrRelayPool
import org.skepsun.kototoro.community.nostr.NostrUnsignedEvent
import org.skepsun.kototoro.core.db.MangaDatabase
import org.skepsun.kototoro.core.prefs.AppSettings
import org.skepsun.kototoro.core.util.ChineseConverter
import org.skepsun.kototoro.parsers.model.Content
import org.skepsun.kototoro.parsers.model.ContentRating
import org.skepsun.kototoro.parsers.util.runCatchingCancellable
import java.text.Normalizer
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/** A work as the community knows it: what it is, every key that names it, and whether it is adult. */
data class CommunityWorkIdentity(
    val medium: CommunityMedium,
    val contentType: String,
    val title: String,
    val keys: List<String>,
    val nsfw: Boolean,
)

/** Why a rating could not be made; the UI says each differently. */
enum class CommunityRatingRefusal { DISABLED, NO_IDENTITY, NO_KEYS, NSFW }

/**
 * The reader's side of community ratings (plan §7): naming works, rating them, and getting the signed events to the
 * relays. Every event goes to the outbox first and leaves it only once enough relays stored it, so a rating made offline
 * or during a relay outage is published later rather than lost.
 */
@Singleton
class CommunityRepository @Inject constructor(
    private val settings: AppSettings,
    private val keyStore: CommunityKeyStore,
    private val db: MangaDatabase,
    private val relayClient: NostrRelayClient,
    private val searchApi: CommunityExternalSearchApi,
    private val snapshots: CommunitySnapshotRepository,
    private val outboxScheduler: CommunityOutboxWorker.Scheduler,
) {

    private val dao get() = db.getCommunityDao()
    private val outboxLock = Mutex()
    private val json = Json { ignoreUnknownKeys = true }

    val isEnabled: Boolean get() = settings.isCommunityEnabled

    fun observeMyRating(mangaId: Long): Flow<Int?> = dao.observeRating(mangaId).map { it?.stars }

    /**
     * The keys [content] is published under. With [resolve], AniList and Bangumi are searched when their policy says so
     * (one search per site per work, retried only after failures); without it, only what is already known is used.
     */
    suspend fun identity(content: Content, trackerRefs: List<CommunityExternalRef>, resolve: Boolean): CommunityWorkIdentity {
        val contentType = content.source.contentType.name
        val medium = CommunityMedium.of(contentType)
        val titles = listOf(content.title) + content.altTitles
        val records = if (resolve) resolve(content.id, medium, titles, trackerRefs) else storedRecords(content.id)
        val refs = CommunityExternalResolution.refs(trackerRefs, records)
        return CommunityWorkIdentity(
            medium = medium,
            contentType = contentType,
            title = content.title,
            keys = CommunityWorkKeys.keys(medium, titles, refs, ::fold),
            nsfw = content.contentRating == ContentRating.ADULT || contentType.startsWith("HENTAI_"),
        )
    }

    suspend fun summary(identity: CommunityWorkIdentity): CommunityWorkSummary? =
        runCatchingCancellable { snapshots.workSummary(identity.keys) }.getOrNull()

    fun refusal(identity: CommunityWorkIdentity): CommunityRatingRefusal? = when {
        !settings.isCommunityEnabled -> CommunityRatingRefusal.DISABLED
        !keyStore.hasIdentity -> CommunityRatingRefusal.NO_IDENTITY
        identity.keys.isEmpty() -> CommunityRatingRefusal.NO_KEYS
        identity.nsfw && !settings.isCommunityNsfwRatingEnabled -> CommunityRatingRefusal.NSFW
        else -> null
    }

    /** Rates the work [stars] of 10 and queues the event; returns the refusal instead when it may not be rated. */
    suspend fun rate(mangaId: Long, identity: CommunityWorkIdentity, stars: Int): CommunityRatingRefusal? {
        refusal(identity)?.let { return it }
        val keys = keyStore.keys() ?: return CommunityRatingRefusal.NO_IDENTITY
        val previous = dao.findRating(mangaId)
        val createdAt = maxOf(nowSeconds(), (previous?.createdAt ?: 0) + 1)
        val draft = CommunityEventSchema.rating(
            identity.keys, identity.medium.wire, identity.contentType, stars, identity.title, identity.nsfw, createdAt,
        )
        val rating = withContext(Dispatchers.Default) {
            NostrKeys.sign(NostrPow.mine(draft, keys.publicKeyHex, powRating(), CommunityEventSchema.POW_TAG), keys)
        }
        val address = CommunityEventSchema.ratingAddress(identity.medium.wire, identity.keys)
        // The new rating replaces the old one, so an old one still waiting to be sent is not sent at all.
        previous?.let { dao.deleteOutbox(it.eventId) }
        // A work that gained a strong key since it was last rated has a new address; the old rating must not keep counting.
        if (previous != null && previous.address != address) {
            enqueue(sign(CommunityEventSchema.ratingWithdrawal(keys.publicKeyHex, previous.address, createdAt), keys))
        }
        enqueue(rating)
        dao.upsertRating(CommunityRatingEntity(mangaId, stars, address, rating.id, createdAt))
        flushOutbox()
        return null
    }

    /** Takes the reader's rating back, everywhere it was published. */
    suspend fun withdraw(mangaId: Long) {
        val previous = dao.findRating(mangaId) ?: return
        dao.deleteOutbox(previous.eventId)
        val keys = keyStore.keys()
        if (keys != null) {
            val createdAt = maxOf(nowSeconds(), previous.createdAt + 1)
            enqueue(sign(CommunityEventSchema.ratingWithdrawal(keys.publicKeyHex, previous.address, createdAt), keys))
        }
        dao.deleteRating(mangaId)
        flushOutbox()
    }

    /**
     * Publishes what is due in the outbox and returns how many events are left in it, due or not. Anything left gets
     * the background worker, which keeps trying once the device is online.
     */
    suspend fun flushOutbox(): Int = outboxLock.withLock {
        val now = System.currentTimeMillis()
        val due = dao.findDueOutbox(now, OUTBOX_BATCH)
        if (due.isNotEmpty()) {
            // One relay is enough: the indexer archives everything it reads, so the archive, not a second relay, is
            // what keeps the event; and from mainland China only one relay both connects and accepts writes (plan §5).
            val pool = NostrRelayPool(relayClient, relays(), minAccepted = 1)
            for (entry in due) publishQueued(pool, entry, now)
        }
        dao.countOutbox().also { left -> if (left > 0) outboxScheduler.enqueue() }
    }

    private suspend fun publishQueued(pool: NostrRelayPool, entry: CommunityOutboxEntity, now: Long) {
        val event = runCatching { NostrEvent.fromJson(json.parseToJsonElement(entry.eventJson).jsonObject) }.getOrNull()
        // Unreadable, or refused for about a week of retries: no later try will do better.
        if (event == null || entry.attempts >= MAX_ATTEMPTS) {
            dao.deleteOutbox(entry.eventId)
            return
        }
        val result = runCatchingCancellable { pool.publish(event) }.getOrNull()
        if (result?.isPublished == true) {
            dao.deleteOutbox(entry.eventId)
        } else {
            val attempts = entry.attempts + 1
            dao.upsertOutbox(
                entry.copy(
                    attempts = attempts,
                    nextAttemptAt = now + retryDelay(attempts),
                    lastError = result?.results?.joinToString { "${it.relay}: ${it.message}" }?.take(500) ?: "no relay answered",
                ),
            )
        }
    }

    private suspend fun enqueue(event: NostrEvent) {
        dao.upsertOutbox(
            CommunityOutboxEntity(
                eventId = event.id,
                eventJson = event.toJson().toString(),
                kind = event.kind,
                createdAt = event.createdAt,
                attempts = 0,
                nextAttemptAt = 0,
                lastError = null,
            ),
        )
    }

    private suspend fun sign(event: NostrUnsignedEvent, keys: NostrKeyPair) = withContext(Dispatchers.Default) { NostrKeys.sign(event, keys) }

    private suspend fun powRating(): Int = runCatchingCancellable { snapshots.manifest()?.powRating }.getOrNull() ?: DEFAULT_POW_RATING

    /** What a comment needs to count: the index's current difficulty, or the default before its first manifest. */
    suspend fun powComment(): Int = runCatchingCancellable { snapshots.manifest()?.powComment }.getOrNull() ?: DEFAULT_POW_COMMENT

    /** Every relay the community reads and writes: the built-in ones and whatever the index adds (plan §5.1). */
    suspend fun relays(): List<String> = (DEFAULT_RELAYS + snapshots.relays().orEmpty()).distinct()

    fun keys(): NostrKeyPair? = keyStore.keys()

    /** Signs, queues and tries to publish an event built elsewhere (comments, deletions, reports). */
    suspend fun publish(event: NostrUnsignedEvent, keys: NostrKeyPair, pow: Int = 0): NostrEvent {
        val signed = withContext(Dispatchers.Default) {
            NostrKeys.sign(NostrPow.mine(event, keys.publicKeyHex, pow, CommunityEventSchema.POW_TAG), keys)
        }
        enqueue(signed)
        flushOutbox()
        return signed
    }

    /** The reader's own events of [kind] that no relay has stored yet. */
    suspend fun pendingEvents(kind: Int): List<NostrEvent> = dao.findOutboxByKind(kind).mapNotNull { entry ->
        runCatching { NostrEvent.fromJson(json.parseToJsonElement(entry.eventJson).jsonObject) }.getOrNull()
    }

    private suspend fun storedRecords(mangaId: Long): List<CommunityResolutionRecord> =
        dao.findResolutions(mangaId).mapNotNull { it.toRecord() }

    private suspend fun resolve(
        mangaId: Long,
        medium: CommunityMedium,
        titles: List<String>,
        trackerRefs: List<CommunityExternalRef>,
    ): List<CommunityResolutionRecord> {
        val stored = storedRecords(mangaId).associateBy { it.service }
        val now = System.currentTimeMillis()
        val titleKeys = CommunityWorkKeys.keys(medium, titles, fold = ::fold)
        val kind = if (medium == CommunityMedium.VIDEO) CommunityExternalSearchKind.VIDEO else CommunityExternalSearchKind.BOOK
        return CommunityExternalResolution.SEARCHED.mapNotNull { service ->
            val previous = stored[service]
            if (!CommunityExternalResolution.shouldSearch(service, medium, trackerRefs, previous, now)) return@mapNotNull previous
            val site = CommunityExternalSite.valueOf(service.name)
            var matched: List<CommunityExternalRef>? = emptyList()
            for (query in CommunityWorkKeys.searchTitles(titles).take(SEARCH_TITLES)) {
                val hits = runCatchingCancellable { searchApi.search(site, query, kind) }.getOrNull()
                if (hits == null) {
                    matched = null
                    break
                }
                matched = CommunityExternalMatch.matches(
                    medium, titleKeys, hits, { service }, CommunityExternalHit::id, CommunityExternalHit::format,
                    CommunityExternalHit::titles, ::fold,
                )
                if (matched.isNotEmpty()) break
            }
            CommunityExternalResolution.record(service, matched, previous, now).also { record ->
                dao.upsertResolution(
                    CommunityResolutionEntity(mangaId, service.name, record.status.name, record.remoteId, record.checkedAt, record.failures),
                )
            }
        }
    }

    private fun CommunityResolutionEntity.toRecord(): CommunityResolutionRecord? = CommunityResolutionRecord(
        service = CommunityExternalService.of(service) ?: return null,
        status = runCatching { CommunityResolutionStatus.valueOf(status) }.getOrNull() ?: return null,
        remoteId = remoteId,
        checkedAt = checkedAt,
        failures = failures,
    )

    companion object {

        /**
         * Always written to, together with whatever the manifest adds. The first two answered a direct WebSocket upgrade
         * from mainland China on 2026-11-20 and accept anyone's writes; the major relays after them are blocked there by
         * SNI and serve readers elsewhere or behind a VPN (plan §5).
         */
        val DEFAULT_RELAYS = listOf(
            "wss://yabu.me",
            "wss://relay.nostr.wirednet.jp",
            "wss://nos.lol",
            "wss://relay.primal.net",
            "wss://nostr.mom",
            "wss://relay.damus.io",
        )

        private const val DEFAULT_POW_RATING = 16
        private const val DEFAULT_POW_COMMENT = 20
        private const val OUTBOX_BATCH = 20
        private const val SEARCH_TITLES = 2
        private const val MAX_RETRY_DELAY = 6 * 60 * 60 * 1000L

        /** A minute doubling to six hours: thirty tries span about a week. */
        private const val MAX_ATTEMPTS = 30

        /** The platform fold of plan §3.2: NFKC, traditional to simplified Chinese, then invariant lowercase. */
        fun fold(text: String): String =
            ChineseConverter.t2s(Normalizer.normalize(text, Normalizer.Form.NFKC)).lowercase(Locale.ROOT)

        /** A minute, then doubling up to six hours. */
        fun retryDelay(attempts: Int): Long = (60_000L shl (attempts - 1).coerceIn(0, 20)).coerceAtMost(MAX_RETRY_DELAY)

        private fun nowSeconds() = System.currentTimeMillis() / 1000
    }
}
