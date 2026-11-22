package org.skepsun.kototoro.core.library.community

import androidx.room.immediateTransaction
import androidx.room.useWriterConnection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.skepsun.kototoro.community.data.CommunityOutboxEntity
import org.skepsun.kototoro.community.data.CommunityRatingEntity
import org.skepsun.kototoro.community.data.CommunityResolutionEntity
import org.skepsun.kototoro.community.domain.CommunityChartEntry
import org.skepsun.kototoro.community.domain.CommunityCommentFilter
import org.skepsun.kototoro.community.domain.CommunityCommentThreads
import org.skepsun.kototoro.community.domain.CommunityExternalMatch
import org.skepsun.kototoro.community.domain.CommunityExternalRef
import org.skepsun.kototoro.community.domain.CommunityExternalResolution
import org.skepsun.kototoro.community.domain.CommunityExternalService
import org.skepsun.kototoro.community.domain.CommunityGenres
import org.skepsun.kototoro.community.domain.CommunitySources
import org.skepsun.kototoro.community.domain.CommunityWorkSource
import org.skepsun.kototoro.community.nostr.CommunitySourceHint
import org.skepsun.kototoro.community.domain.CommunityHighlights
import org.skepsun.kototoro.community.domain.CommunityMedium
import org.skepsun.kototoro.community.domain.CommunityModeration
import org.skepsun.kototoro.community.domain.CommunityRatingRefusal
import org.skepsun.kototoro.community.domain.CommunityRecommendations
import org.skepsun.kototoro.community.domain.CommunityResolutionRecord
import org.skepsun.kototoro.community.domain.CommunityResolutionStatus
import org.skepsun.kototoro.community.domain.CommunityThread
import org.skepsun.kototoro.community.domain.CommunityWorkIdentity
import org.skepsun.kototoro.community.domain.CommunityWorkKeys
import org.skepsun.kototoro.community.domain.CommunityWorkSummary
import org.skepsun.kototoro.community.external.CommunityExternalHit
import org.skepsun.kototoro.community.external.CommunityExternalSearchApi
import org.skepsun.kototoro.community.external.CommunityExternalSearchKind
import org.skepsun.kototoro.community.external.CommunityExternalSite
import org.skepsun.kototoro.community.nostr.CommunityComment
import org.skepsun.kototoro.community.nostr.CommunityEventSchema
import org.skepsun.kototoro.community.nostr.CommunityProfiles
import org.skepsun.kototoro.community.nostr.CommunityRecentPublications
import org.skepsun.kototoro.community.nostr.CommunityReportType
import org.skepsun.kototoro.community.nostr.NostrBech32
import org.skepsun.kototoro.community.nostr.NostrEvent
import org.skepsun.kototoro.community.nostr.NostrFilter
import org.skepsun.kototoro.community.nostr.NostrKeyPair
import org.skepsun.kototoro.community.nostr.NostrKeys
import org.skepsun.kototoro.community.nostr.NostrPow
import org.skepsun.kototoro.community.nostr.NostrRelayClient
import org.skepsun.kototoro.community.nostr.NostrRelayPool
import org.skepsun.kototoro.community.nostr.NostrUnsignedEvent
import org.skepsun.kototoro.core.db.MangaDatabase
import org.skepsun.kototoro.core.library.SourceSuggestionCommunity
import org.skepsun.kototoro.core.library.SourceSuggestionPreferences
import org.skepsun.kototoro.core.library.SourceTracking
import org.skepsun.kototoro.core.library.filter
import org.skepsun.kototoro.core.source.SourceContent
import org.skepsun.kototoro.core.source.SourceFilter
import org.skepsun.kototoro.core.source.SourceRef
import org.skepsun.kototoro.core.source.SourceRuntime
import org.skepsun.kototoro.core.source.isNsfw
import org.skepsun.kototoro.suggestions.domain.LibraryWork
import org.skepsun.kototoro.suggestions.domain.SuggestionProfile
import org.skepsun.kototoro.tracking.discovery.PublicTrackingSite
import kotlin.coroutines.CoroutineContext

/**
 * The reader's community identity (plan §2). A host seals the secret as well as it can; it is never part of backups,
 * and the reader keeps it as the `nsec` recovery key instead.
 */
interface CommunityIdentityStore {
    val hasIdentity: Boolean

    /** The identity, or null when there is none or the host can no longer open it. */
    fun keys(): NostrKeyPair?

    fun store(keys: NostrKeyPair)

    fun clear()
}

/** What this reader chose not to see: authors they blocked and comments they reported. Stays on this device. */
interface CommunityBlocklist {
    val filter: CommunityCommentFilter

    fun block(pubkey: String)

    fun hide(eventId: String)
}

/** The two switches of the services page; Android's `community_enabled` and `community_nsfw_ratings`. */
data class CommunitySwitches(
    val enabled: Boolean = false,
    val nsfwRatings: Boolean = false,
    /** Android's `community_share_sources`: name the source a rating or comment was made on ([CommunitySources]). */
    val shareSources: Boolean = true,
)

/** A source of the reader's own where a community work can be looked for, and the entry its readers named there. */
data class CommunitySourceOption(
    val source: SourceRef,
    /** The entry the work's readers named on this source or site; null for one of the reader's other sources. */
    val listed: CommunityWorkSource?,
)

/** One comment as a comment list shows it. */
data class CommunityCommentItem(
    val comment: CommunityComment,
    val event: NostrEvent,
    /** The author's chosen name, or a short form of their public key. */
    val author: String,
    val isMine: Boolean,
    /** Written on this device and not yet stored by any relay. */
    val isPending: Boolean,
)

data class CommunityCommentPage(
    val threads: List<CommunityThread<CommunityCommentItem>>,
    /** How many relays answered; zero means the list says nothing about what exists. */
    val relaysAnswered: Int,
)

/**
 * Ratings of a work newer than the index snapshot, which the snapshot's score cannot show yet: how many readers rated it
 * since, and whether the reader's own rating is one of them.
 */
data class CommunityFreshRatings(val count: Int = 0, val includesMine: Boolean = false)

/** The community chart of a window, or while no chart has entries yet ([isFallback]), every rated work. */
data class CommunityChartPage(val window: String, val entries: List<CommunityChartEntry>, val isFallback: Boolean)

/**
 * The community for hosts on the shared source protocol (plan §7): Android's `CommunityRepository`,
 * `CommunityCommentsRepository` and `CommunitySuggestionsRepository` over [SourceContent] and the shared database.
 * Every event goes to the outbox first and leaves it only once a relay stored it, so a rating made offline is published
 * later rather than lost; the host decides when [flushOutbox] runs again.
 */
class CommunityService(
    private val database: MangaDatabase,
    private val identities: CommunityIdentityStore,
    private val blocklist: CommunityBlocklist,
    private val snapshots: CommunitySnapshots,
    private val relayClient: NostrRelayClient,
    private val searchApi: CommunityExternalSearchApi?,
    private val switches: () -> CommunitySwitches,
    /** The platform fold of plan §3.2: NFKC, traditional to simplified Chinese, then lowercase. */
    private val fold: (String) -> String,
    private val io: CoroutineContext = Dispatchers.Default,
    private val nowMillis: () -> Long = { kotlin.time.Clock.System.now().toEpochMilliseconds() },
) {
    private val dao get() = database.getCommunityDao()
    private val outboxLock = Mutex()
    private val profileLock = Mutex()
    private val recent = CommunityRecentPublications()
    private val json = Json { ignoreUnknownKeys = true }

    val isEnabled: Boolean get() = switches().enabled

    // Identity

    val hasIdentity: Boolean get() = identities.hasIdentity

    fun keys(): NostrKeyPair? = identities.keys()

    fun createIdentity(): NostrKeyPair = NostrKeyPair.generate().also(identities::store)

    /** From a recovery key a reader pasted; null when it is not one. */
    fun restoreIdentity(recoveryKey: String): NostrKeyPair? =
        NostrBech32.decode(recoveryKey, NostrBech32.SECRET_PREFIX)?.let(NostrKeyPair::fromSecretHex)?.also(identities::store)

    fun recoveryKey(): String? = identities.keys()?.let(NostrBech32::encodeSecretKey)

    // Works and ratings

    /**
     * The keys [content] is published under. With [resolve], AniList and Bangumi are searched when their policy says so
     * (one search per site per work, retried only after failures); without it, only what is already known is used.
     */
    suspend fun identity(
        content: SourceContent,
        resolve: Boolean,
        /** Bindings the host already holds (Android's scrobblers), ahead of the shared tracking table's. */
        extraRefs: List<CommunityExternalRef> = emptyList(),
    ): CommunityWorkIdentity = withContext(io) {
        val contentType = content.source.contentType
        val medium = CommunityMedium.of(contentType)
        val titles = listOf(content.title) + content.altTitles
        val trackerRefs = (extraRefs + trackerRefs(content.id)).distinctBy { it.service }
        val records = if (resolve && searchApi != null) resolve(content.id, medium, titles, trackerRefs)
            else storedRecords(content.id)
        CommunityWorkIdentity(
            medium = medium,
            contentType = contentType,
            title = content.title,
            keys = CommunityWorkKeys.keys(medium, titles, CommunityExternalResolution.refs(trackerRefs, records), fold),
            nsfw = content.isNsfw,
            genres = CommunityGenres.of(content.tags.map { it.title }),
            source = content.takeIf {
                switches().shareSources && !it.isNsfw && CommunitySources.publishable(it.source.name, it.source.contentType)
            }?.let { CommunityWorkSource(it.source.name, it.url, CommunitySources.hostOf(it.publicUrl), readers = 0) },
        )
    }

    suspend fun summary(identity: CommunityWorkIdentity): CommunityWorkSummary? =
        attempt { snapshots.workSummary(identity.keys) }

    /**
     * Readers who rated the work after the snapshot was built, read straight from the relays, with the reader's own
     * rating counted from this device even when no relay answered. The snapshot's score does not include them yet.
     */
    suspend fun freshRatings(contentId: Long, identity: CommunityWorkIdentity): CommunityFreshRatings {
        if (identity.keys.isEmpty()) return CommunityFreshRatings()
        val since = attempt { snapshots.manifest() }?.generatedAt ?: 0L
        val me = identities.keys()?.publicKeyHex
        val mine = dao.findRating(contentId)?.takeIf { it.createdAt > since }
        val pow = powRating()
        val events = attempt {
            NostrRelayPool(relayClient, relays()).query(listOf(NostrFilter(
                kinds = listOf(CommunityEventSchema.KIND_RATING),
                tags = mapOf("i" to identity.keys.take(MAX_QUERY_KEYS)),
                since = since + 1,
            )), timeoutMillis = FRESH_TIMEOUT_MILLIS)
        }?.events.orEmpty()
        val authors = events.filter { it.pubkey == me || NostrPow.satisfies(it, pow, CommunityEventSchema.POW_TAG) }
            .mapTo(HashSet()) { it.pubkey }
        if (mine != null && me != null) authors += me
        return CommunityFreshRatings(authors.size, includesMine = mine != null)
    }

    fun refusal(identity: CommunityWorkIdentity): CommunityRatingRefusal? {
        val switches = switches()
        return when {
            !switches.enabled -> CommunityRatingRefusal.DISABLED
            !identities.hasIdentity -> CommunityRatingRefusal.NO_IDENTITY
            identity.keys.isEmpty() -> CommunityRatingRefusal.NO_KEYS
            identity.nsfw && !switches.nsfwRatings -> CommunityRatingRefusal.NSFW
            else -> null
        }
    }

    fun observeMyRating(contentId: Long): Flow<Int?> = dao.observeRating(contentId).map { it?.stars }

    /** Rates the work [stars] of 10 and queues the event; returns the refusal instead when it may not be rated. */
    suspend fun rate(contentId: Long, identity: CommunityWorkIdentity, stars: Int): CommunityRatingRefusal? {
        refusal(identity)?.let { return it }
        val keys = identities.keys() ?: return CommunityRatingRefusal.NO_IDENTITY
        val previous = dao.findRating(contentId)
        val createdAt = maxOf(nowSeconds(), (previous?.createdAt ?: 0) + 1)
        val draft = CommunityEventSchema.rating(
            identity.keys, identity.medium.wire, identity.contentType, stars, identity.title, identity.nsfw, createdAt,
            genres = identity.genres,
            source = identity.source?.hint(),
        )
        val rating = sign(draft, keys, powRating())
        val address = CommunityEventSchema.ratingAddress(identity.medium.wire, identity.keys)
        val withdrawal = previous?.takeIf { it.address != address }?.let {
            // A work that gained a strong key since it was last rated has a new address; the old one must not keep counting.
            sign(CommunityEventSchema.ratingWithdrawal(keys.publicKeyHex, it.address, createdAt), keys)
        }
        outboxLock.withLock {
            database.useWriterConnection { writer ->
                writer.immediateTransaction {
                    // The new rating replaces the old one, so an old one still waiting to be sent is not sent at all.
                    previous?.let { dao.deleteOutbox(it.eventId) }
                    withdrawal?.let { dao.upsertOutbox(it.queued()) }
                    dao.upsertOutbox(rating.queued())
                    dao.upsertRating(CommunityRatingEntity(contentId, stars, address, rating.id, createdAt))
                }
            }
        }
        flushOutbox()
        return null
    }

    /** Takes the reader's rating back, everywhere it was published. */
    suspend fun withdraw(contentId: Long) {
        val previous = dao.findRating(contentId) ?: return
        val keys = identities.keys()
        val withdrawal = keys?.let {
            sign(CommunityEventSchema.ratingWithdrawal(it.publicKeyHex, previous.address, maxOf(nowSeconds(), previous.createdAt + 1)), it)
        }
        outboxLock.withLock {
            database.useWriterConnection { writer ->
                writer.immediateTransaction {
                    dao.deleteOutbox(previous.eventId)
                    withdrawal?.let { dao.upsertOutbox(it.queued()) }
                    dao.deleteRating(contentId)
                }
            }
        }
        flushOutbox()
    }

    // Outbox

    /**
     * Publishes what is due in the outbox and returns how many events no relay has accepted yet, due or not. Events one
     * relay accepted stay as well, without counting here: they are public already, and are dropped once a second relay
     * takes them or the index has run since ([confirmQueued]).
     */
    suspend fun flushOutbox(force: Boolean = false): Int = outboxLock.withLock {
        val now = nowMillis()
        val due = dao.findDueOutbox(if (force) Long.MAX_VALUE else now, OUTBOX_BATCH)
        val unconfirmed = dao.findDueUnconfirmed(now, OUTBOX_BATCH)
        if (due.isNotEmpty() || unconfirmed.isNotEmpty()) {
            val relays = relays()
            // One relay is enough to count as published: from mainland China only one relay both connects and accepts
            // writes (plan §5). Until a second one holds it or the indexer has archived it, the outbox keeps a copy.
            val pool = NostrRelayPool(relayClient, relays, minAccepted = 1)
            for (entry in due) publishQueued(pool, entry, now)
            if (unconfirmed.isNotEmpty()) {
                val indexedAt = attempt { snapshots.manifest() }?.generatedAt?.times(1000) ?: 0L
                for (entry in unconfirmed) confirmQueued(relays, entry, now, indexedAt)
            }
        }
        dao.countOutbox()
    }

    suspend fun pendingCount(): Int = dao.countOutbox()

    private suspend fun publishQueued(pool: NostrRelayPool, entry: CommunityOutboxEntity, now: Long) {
        val event = entry.eventOrNull()
        // Unreadable, or refused for about a week of retries: no later try will do better.
        if (event == null || entry.attempts >= MAX_ATTEMPTS) {
            dao.deleteOutbox(entry.eventId)
            return
        }
        val result = attempt { pool.publish(event) }
        if (result?.isPublished == true) {
            recent.remember(event)
            settle(entry, result.results.filter { it.accepted }.map { it.relay }, now, publishedAt = now)
        } else {
            val attempts = entry.attempts + 1
            dao.upsertOutbox(entry.copy(
                attempts = attempts,
                nextAttemptAt = now + retryDelay(attempts),
                lastError = result?.results?.joinToString { "${it.relay}: ${it.message}" }?.take(500) ?: "no relay answered",
            ))
        }
    }

    /**
     * An accepted event that only [CONFIRMING_RELAYS] relays would make safe: offered again to the relays that do not
     * have it, and let go once enough hold it, once the index has run after it was published (the archive keeps it from
     * then on), or after [MAX_UNCONFIRMED_MILLIS], when it has had every chance.
     */
    private suspend fun confirmQueued(relays: List<String>, entry: CommunityOutboxEntity, now: Long, indexedAt: Long) {
        val event = entry.eventOrNull()
        if (event == null || indexedAt > entry.publishedAt + INDEX_MARGIN_MILLIS ||
            now - entry.publishedAt > MAX_UNCONFIRMED_MILLIS) {
            dao.deleteOutbox(entry.eventId)
            return
        }
        val accepted = entry.acceptedRelays.lines().filter(String::isNotBlank)
        val others = relays - accepted.toSet()
        val result = if (others.isEmpty()) null else attempt { NostrRelayPool(relayClient, others, minAccepted = 1).publish(event) }
        settle(entry, accepted + result?.results.orEmpty().filter { it.accepted }.map { it.relay }, now, entry.publishedAt)
    }

    /** Drops [entry] once [CONFIRMING_RELAYS] relays hold it, otherwise keeps it for the next confirmation round. */
    private suspend fun settle(entry: CommunityOutboxEntity, accepted: List<String>, now: Long, publishedAt: Long) {
        val relays = accepted.distinct()
        if (relays.size >= CONFIRMING_RELAYS) {
            dao.deleteOutbox(entry.eventId)
        } else {
            dao.upsertOutbox(entry.copy(
                publishedAt = publishedAt,
                acceptedRelays = relays.joinToString("\n"),
                nextAttemptAt = now + CONFIRM_INTERVAL_MILLIS,
                lastError = null,
            ))
        }
    }

    /** Signs, queues and tries to publish an event built elsewhere (comments, deletions, reports). */
    private suspend fun publish(event: NostrUnsignedEvent, keys: NostrKeyPair, pow: Int = 0): NostrEvent {
        val signed = sign(event, keys, pow)
        outboxLock.withLock { dao.upsertOutbox(signed.queued()) }
        flushOutbox()
        return signed
    }

    // Comments

    suspend fun comments(identity: CommunityWorkIdentity, summary: CommunityWorkSummary?): CommunityCommentPage {
        val pool = NostrRelayPool(relayClient, relays())
        val keys = identity.keys.take(MAX_QUERY_KEYS)
        val me = identities.keys()?.publicKeyHex
        val pow = powComment()
        val result = pool.query(listOf(NostrFilter(
            kinds = listOf(CommunityEventSchema.KIND_COMMENT), tags = mapOf("I" to keys), limit = COMMENT_LIMIT,
        )))
        val published = result.events.filter { it.pubkey == me || NostrPow.satisfies(it, pow, CommunityEventSchema.POW_TAG) }
        val pending = pendingEvents(CommunityEventSchema.KIND_COMMENT)
            .filter { event -> event.tagValues("I").any { it in keys } && published.none { it.id == event.id } }
        val events = (published + pending + recent.comments(keys).filter { it.pubkey == me }).distinctBy(NostrEvent::id)
        val comments = events.mapNotNull { event -> CommunityEventSchema.parseComment(event)?.let { it to event } }
        val answered = result.relays.count { it.error == null }
        if (comments.isEmpty()) return CommunityCommentPage(emptyList(), answered)

        val deletions = attempt {
            pool.query(listOf(NostrFilter(
                kinds = listOf(CommunityEventSchema.KIND_DELETION), tags = mapOf("e" to comments.map { it.first.id }),
            )))
        }?.events.orEmpty()
            .mapNotNull(CommunityEventSchema::parseDeletion)
            .groupBy({ it.pubkey }, { it.eventIds })
            .mapValues { (_, sets) -> sets.flatten().toSet() }
        val names = profiles(pool, comments.map { it.first.pubkey }.distinct()).mapValues { CommunityProfiles.nickname(it.value) }
        val moderation = attempt { snapshots.moderation() } ?: CommunityModeration()
        val filter = CommunityCommentFilter(
            blockedPubkeys = moderation.blockedPubkeys.toSet(),
            blockedEvents = moderation.blockedEvents.toSet() + summary?.hiddenComments.orEmpty(),
            blockedWords = moderation.blockedWords,
        ) + blocklist.filter
        val pendingIds = pending.mapTo(HashSet()) { it.id }
        val items = comments.map { (comment, event) ->
            CommunityCommentItem(
                comment = comment,
                event = event,
                author = names[comment.pubkey] ?: shortKey(comment.pubkey),
                isMine = comment.pubkey == me,
                isPending = comment.id in pendingIds,
            )
        }
        val visible = CommunityCommentThreads.visible(
            items, filter, deletions, { it.comment.id }, { it.comment.pubkey }, { it.comment.content },
        )
        return CommunityCommentPage(
            CommunityCommentThreads.build(visible, { it.comment.id }, { it.comment.parentId }, { it.comment.createdAt }),
            answered,
        )
    }

    /** Posts a comment, or a reply to [parent]; returns the refusal when the reader may not comment on this work. */
    suspend fun postComment(
        identity: CommunityWorkIdentity,
        text: String,
        parent: NostrEvent?,
        spoiler: Boolean,
        language: String?,
    ): CommunityRatingRefusal? {
        refusal(identity)?.let { return it }
        return if (postCommentEvent(identity, text, parent, spoiler, language) == null) CommunityRatingRefusal.NO_IDENTITY else null
    }

    /** [postComment] for hosts that tell the reader whether it reached a relay ([isPublished]); null when refused. */
    suspend fun postCommentEvent(
        identity: CommunityWorkIdentity,
        text: String,
        parent: NostrEvent?,
        spoiler: Boolean,
        language: String?,
    ): NostrEvent? {
        if (refusal(identity) != null) return null
        val keys = identities.keys() ?: return null
        val draft = CommunityEventSchema.comment(
            keys = identity.keys,
            content = text,
            title = identity.title,
            createdAt = nowSeconds(),
            parent = parent,
            spoiler = spoiler,
            nsfw = identity.nsfw,
            language = language,
            source = identity.source?.hint(),
        )
        return publish(draft, keys, powComment())
    }

    /** Whether a relay has taken the event; true too once it left the outbox altogether. */
    suspend fun isPublished(eventId: String): Boolean = dao.findOutbox(eventId)?.let { it.publishedAt > 0 } ?: true

    /** The event id of the reader's current rating of [contentId], to ask [isPublished] about. */
    suspend fun ratingEventId(contentId: Long): String? = dao.findRating(contentId)?.eventId

    suspend fun deleteComment(event: NostrEvent) {
        val keys = identities.keys() ?: return
        if (event.pubkey != keys.publicKeyHex) return
        blocklist.hide(event.id)
        publish(CommunityEventSchema.deletion(event, nowSeconds()), keys)
    }

    /** Hides the comment here at once and tells the index; several trusted reports hide it for everyone. */
    suspend fun report(event: NostrEvent, type: CommunityReportType) {
        blocklist.hide(event.id)
        val keys = identities.keys() ?: return
        publish(CommunityEventSchema.report(event, type, nowSeconds()), keys)
    }

    fun block(pubkey: String) = blocklist.block(pubkey)

    // Nicknames

    suspend fun nickname(): String? {
        val keys = identities.keys() ?: return null
        return profiles(NostrRelayPool(relayClient, relays()), listOf(keys.publicKeyHex))[keys.publicKeyHex]
            ?.let(CommunityProfiles::nickname)
    }

    /** Publishes [nickname]; true when a relay stored it, false when it waits in the outbox. */
    suspend fun setNickname(nickname: String): Boolean = profileLock.withLock {
        val keys = checkNotNull(identities.keys())
        val pubkey = keys.publicKeyHex
        val previous = profiles(NostrRelayPool(relayClient, relays()), listOf(pubkey))[pubkey]
        val event = sign(CommunityProfiles.update(nickname, nowSeconds(), previous), keys)
        outboxLock.withLock {
            database.useWriterConnection { writer ->
                writer.immediateTransaction {
                    pendingEvents(CommunityProfiles.KIND).filter { it.pubkey == pubkey }.forEach { dao.deleteOutbox(it.id) }
                    dao.upsertOutbox(event.queued())
                }
            }
        }
        flushOutbox()
        recent.profiles(listOf(pubkey)).any { it.id == event.id }
    }

    // Charts and suggestions

    /**
     * The suggestions page's chart for [window] (`week`, `month`, `all`) across media, best first. A quiet week next to
     * a filled all-time chart is still a chart; only an index without any lists every rated work instead.
     */
    suspend fun chart(window: String, limit: Int = CHART_LIMIT): CommunityChartPage {
        val nsfw = listOf(false) + listOfNotNull(true.takeIf { switches().nsfwRatings })
        suspend fun popular(window: String) = attempt {
            CommunityHighlights.merge(MEDIA.flatMap { medium -> nsfw.map { snapshots.chart(window, medium, it) } }, limit)
        }.orEmpty()
        val popular = popular(window)
        if (popular.isNotEmpty()) return CommunityChartPage(window, popular, isFallback = false)
        if (window != CommunityHighlights.WINDOW_ALL && popular(CommunityHighlights.WINDOW_ALL).isNotEmpty()) {
            return CommunityChartPage(window, emptyList(), isFallback = false)
        }
        val rated = attempt { CommunityHighlights.rated(snapshots.allWorks(), switches().nsfwRatings, limit) }.orEmpty()
        return CommunityChartPage(window, rated, isFallback = true)
    }

    /**
     * Android's community candidates for one suggestions run: works readers with the reader's taste rated highly, looked
     * up in the reader's own sources, plus the community score of every candidate the index already has cached. Nothing
     * about the library leaves the device; only the index's public files are read.
     */
    suspend fun suggestionCandidates(
        runtime: SourceRuntime,
        library: List<LibraryWork>,
        sources: List<SourceRef>,
        preferences: SourceSuggestionPreferences,
        now: Long,
        raw: List<SourceContent>,
        cached: List<SourceContent>,
    ): SourceSuggestionCommunity {
        val empty = SourceSuggestionCommunity(emptyList(), emptyMap())
        if (!isEnabled) return empty
        return attempt {
            val targets = withTimeoutOrNull(TARGETS_TIMEOUT_MILLIS) {
                CommunityRecommendations.targets(
                    library = library,
                    stars = dao.findAllRatings().associate { it.mangaId to it.stars },
                    refs = libraryRefs(),
                    fold = fold,
                    groupsOf = { snapshots.groupsOf(it) },
                    works = { snapshots.works(it) },
                    limit = CHART_LIMIT,
                )
            }.orEmpty()
            val used = library.mapNotNullTo(HashSet()) { it.source }
            val choices = sources.filter { source ->
                !source.contentType.startsWith("HENTAI_") && source.name !in preferences.excludedSources &&
                    (!preferences.languageOnly || preferences.language.accepts(source.locale, preferences.systemLanguage))
            }.sortedWith(compareByDescending<SourceRef> { it.name in preferences.preferredSources }
                .thenByDescending { it.name in used }).groupBy { CommunityMedium.of(it.contentType) }
            val bySource = if (targets.isEmpty()) emptyMap() else (raw + cached).groupBy { it.source.name }
            val profile = SuggestionProfile.of(library, now, preferences.tagWhitelist)
            val candidates = CommunityRecommendations.resolve(
                targets, choices, { bySource[it.name].orEmpty() },
                { source, title ->
                    attempt { runtime.getList(source.name, 0, null, SourceFilter(query = title)) }.orEmpty()
                },
                { listOf(it.title) + it.altTitles },
                { source, item ->
                    item.source.name == source.name && item.title.isNotBlank() &&
                        (item.url.isNotBlank() || item.publicUrl.isNotBlank()) &&
                        !profile.knows(item.id, listOf(item.title) + item.altTitles) &&
                        preferences.copy(excludeNsfw = true).filter(listOf(item)).isNotEmpty()
                },
                fold,
            )
            val keys = raw.associate { item ->
                item.id to CommunityWorkKeys.keys(CommunityMedium.of(item.source.contentType),
                    listOf(item.title) + item.altTitles, fold = fold)
            }.filterValues { it.isNotEmpty() }
            val groups = snapshots.groupsOf(keys, cachedOnly = true) +
                candidates.associate { it.item.id to targets[it.rank].groupId }
            val works = snapshots.works(groups.values.toSet(), cachedOnly = true)
            val nsfwRatings = switches().nsfwRatings
            SourceSuggestionCommunity(candidates, groups.mapNotNull { (id, group) ->
                works[group]?.takeUnless { it.nsfw && !nsfwRatings }?.rating?.let { id to it }
            }.toMap())
        } ?: empty
    }

    // Opening a community work

    /**
     * The reader's sources where the community work [groupId] can be looked for, best first: those the work's readers
     * named (the same source id first, then another of the reader's sources for the same site), each in the order of
     * [sources], which the host sorts by its own preference; then, when [includeOthers], the rest of [sources] that
     * carry its medium. Adult sources never.
     */
    suspend fun sourceOptions(
        groupId: String,
        sources: List<SourceRef>,
        hostOf: (SourceRef) -> String? = { null },
        includeOthers: Boolean = false,
    ): List<CommunitySourceOption> = withContext(io) {
        val summary = attempt { snapshots.works(listOf(groupId))[groupId] }
        val mediums = mediumsOf(summary)
        val usable = sources.filter { !it.contentType.startsWith("HENTAI_") && CommunityMedium.of(it.contentType) in mediums }
        val listed = summary?.sources.orEmpty()
        val byName = usable.mapNotNull { source -> listed.firstOrNull { it.source == source.name }?.let { CommunitySourceOption(source, it) } }
        val byHost = usable.filter { source -> byName.none { it.source == source } }.mapNotNull { source ->
            val host = hostOf(source)?.removePrefix("www.")?.lowercase() ?: return@mapNotNull null
            listed.filter { it.host == host }.maxByOrNull { it.readers }?.let { CommunitySourceOption(source, it) }
        }
        val named = byName + byHost
        val others = if (includeOthers) usable.filter { source -> named.none { it.source == source } }.map { CommunitySourceOption(it, null) }
            else emptyList()
        named + others
    }

    /**
     * A community work as something the reader can open. The index names works by group and title, plus where its
     * readers read it, so a chart or a similar-works rail would otherwise end in a title search. First the reader's
     * own copy ([library]) when one is in the group; then the sources its readers named, best first ([sourceOptions]),
     * where the entry with the named address wins; then up to [FIND_SOURCES] of the reader's other sources. An entry
     * counts when the index puts it in the group, or when its title key matches the work's and the index has not
     * seen it. Null when nothing matches, and the host falls back to its title search.
     */
    suspend fun findWork(
        groupId: String,
        title: String,
        library: List<SourceContent>,
        sources: List<SourceRef>,
        hostOf: (SourceRef) -> String? = { null },
        search: suspend (SourceRef, String) -> List<SourceContent>,
    ): SourceContent? = withContext(io) {
        val summary = attempt { snapshots.works(listOf(groupId))[groupId] }
        val mediums = mediumsOf(summary)
        // The library's own copy: only its key shards that are cached, so this costs nothing on a slow network.
        inGroup(groupId, library.filter { CommunityMedium.of(it.source.contentType) in mediums }, cachedOnly = true)
            ?.let { return@withContext it }
        val options = sourceOptions(groupId, sources, hostOf, includeOthers = true)
        val named = options.filter { it.listed != null }
        val others = options.filter { it.listed == null }.take(FIND_SOURCES)
        for (option in named + others) {
            searchIn(option, groupId, title, mediums, search)?.let { return@withContext it }
        }
        null
    }

    /** [findWork] on one source the reader picked, for the "other sources" list. */
    suspend fun findWorkIn(
        option: CommunitySourceOption,
        groupId: String,
        title: String,
        search: suspend (SourceRef, String) -> List<SourceContent>,
    ): SourceContent? = withContext(io) {
        searchIn(option, groupId, title, mediumsOf(attempt { snapshots.works(listOf(groupId))[groupId] }), search)
    }

    private fun mediumsOf(summary: CommunityWorkSummary?): List<CommunityMedium> =
        summary?.medium?.let { wire -> CommunityMedium.entries.firstOrNull { it.wire == wire } }?.let(::listOf)
            ?: CommunityMedium.entries.filter { it != CommunityMedium.OTHER }

    private suspend fun inGroup(groupId: String, items: List<SourceContent>, cachedOnly: Boolean): SourceContent? {
        val keys = items.associate { item ->
            item.id to CommunityWorkKeys.keys(CommunityMedium.of(item.source.contentType),
                listOf(item.title) + item.altTitles, fold = fold)
        }.filterValues { it.isNotEmpty() }
        if (keys.isEmpty()) return null
        val groups = attempt { snapshots.groupsOf(keys, cachedOnly) }.orEmpty()
        return items.firstOrNull { groups[it.id] == groupId }
    }

    private suspend fun searchIn(
        option: CommunitySourceOption,
        groupId: String,
        title: String,
        mediums: List<CommunityMedium>,
        search: suspend (SourceRef, String) -> List<SourceContent>,
    ): SourceContent? {
        val found = withTimeoutOrNull(FIND_TIMEOUT_MILLIS) { attempt { search(option.source, title) } }.orEmpty()
            .filter { it.title.isNotBlank() && (it.url.isNotBlank() || it.publicUrl.isNotBlank()) && !it.isNsfw }
        if (found.isEmpty()) return null
        val wanted = mediums.mapNotNull { CommunityWorkKeys.titleKey(title, it, fold) }.toSet()
        fun titled(item: SourceContent) = CommunityWorkKeys.keys(CommunityMedium.of(item.source.contentType),
            listOf(item.title) + item.altTitles, fold = fold).any { it in wanted }
        val listed = option.listed?.takeIf { it.source == option.source.name }
        // The address its readers named, as long as the entry is still the same work: a wrong record costs one search.
        listed?.let { entry -> found.firstOrNull { it.url == entry.url } }
            ?.takeIf { titled(it) || inGroup(groupId, listOf(it), cachedOnly = false) != null }
            ?.let { return it }
        return inGroup(groupId, found, cachedOnly = false) ?: found.firstOrNull(::titled)
    }

    // Internals

    /** Every relay the community reads and writes: the built-in ones and whatever the index adds (plan §5.1). */
    suspend fun relays(): List<String> =
        (DEFAULT_RELAYS + attempt { snapshots.relays() }.orEmpty()).filter { it.startsWith("wss://") }.distinct()

    private suspend fun powRating(): Int = attempt { snapshots.manifest()?.powRating } ?: DEFAULT_POW_RATING

    private suspend fun powComment(): Int = attempt { snapshots.manifest()?.powComment } ?: DEFAULT_POW_COMMENT

    private suspend fun sign(event: NostrUnsignedEvent, keys: NostrKeyPair, pow: Int = 0): NostrEvent =
        withContext(Dispatchers.Default) {
            NostrKeys.sign(NostrPow.mine(event, keys.publicKeyHex, pow.coerceIn(0, MAX_POW), CommunityEventSchema.POW_TAG), keys)
        }

    private suspend fun profiles(pool: NostrRelayPool, authors: List<String>): Map<String, NostrEvent> {
        if (authors.isEmpty()) return emptyMap()
        val remote = attempt { CommunityProfiles.query(pool, authors) }.orEmpty()
        val pending = pendingEvents(CommunityProfiles.KIND).filter { it.pubkey in authors }
        return CommunityProfiles.latest(remote + pending + recent.profiles(authors))
    }

    private suspend fun pendingEvents(kind: Int): List<NostrEvent> = dao.findOutboxByKind(kind).mapNotNull { it.eventOrNull() }

    private fun CommunityOutboxEntity.eventOrNull(): NostrEvent? =
        runCatching { NostrEvent.fromJson(json.parseToJsonElement(eventJson).jsonObject) }.getOrNull()

    private fun NostrEvent.queued() = CommunityOutboxEntity(
        eventId = id,
        eventJson = toJson().toString(),
        kind = kind,
        createdAt = createdAt,
        attempts = 0,
        nextAttemptAt = 0,
        lastError = null,
    )

    /** The reader's own tracker bindings for one work, by the community's service names. */
    private suspend fun trackerRefs(contentId: Long): List<CommunityExternalRef> =
        SourceTracking(database).bindings(contentId).mapNotNull { item ->
            val name = PublicTrackingSite.entries.firstOrNull { it.id == item.service }?.name
            CommunityExternalService.of(name)?.let { CommunityExternalRef(it, item.remoteId) }
        }.distinctBy { it.service }

    /** Tracker ids for every library work: the reader's own bindings, then what searches bound. */
    private suspend fun libraryRefs(): Map<Long, List<CommunityExternalRef>> {
        val bound = dao.findBoundResolutions().mapNotNull { row ->
            val service = CommunityExternalService.of(row.service) ?: return@mapNotNull null
            row.mangaId to CommunityExternalRef(service, row.remoteId ?: return@mapNotNull null)
        }
        val tracked = database.getScrobblingDao().findAllByScrobblerEntries().mapNotNull { row ->
            val name = PublicTrackingSite.entries.firstOrNull { it.id == row.scrobbler }?.name
            val service = CommunityExternalService.of(name) ?: return@mapNotNull null
            row.mangaId to CommunityExternalRef(service, row.targetId)
        }
        return (tracked + bound).groupBy({ it.first }, { it.second }).mapValues { (_, list) -> list.distinctBy { it.service } }
    }

    private suspend fun storedRecords(contentId: Long): List<CommunityResolutionRecord> =
        dao.findResolutions(contentId).mapNotNull { it.toRecord() }

    private suspend fun resolve(
        contentId: Long,
        medium: CommunityMedium,
        titles: List<String>,
        trackerRefs: List<CommunityExternalRef>,
    ): List<CommunityResolutionRecord> {
        val api = searchApi ?: return storedRecords(contentId)
        val stored = storedRecords(contentId).associateBy { it.service }
        val now = nowMillis()
        val titleKeys = CommunityWorkKeys.keys(medium, titles, fold = fold)
        val kind = if (medium == CommunityMedium.VIDEO) CommunityExternalSearchKind.VIDEO else CommunityExternalSearchKind.BOOK
        return CommunityExternalResolution.SEARCHED.mapNotNull { service ->
            val previous = stored[service]
            if (!CommunityExternalResolution.shouldSearch(service, medium, trackerRefs, previous, now)) return@mapNotNull previous
            val site = CommunityExternalSite.valueOf(service.name)
            var matched: List<CommunityExternalRef>? = emptyList()
            for (query in CommunityWorkKeys.searchTitles(titles).take(SEARCH_TITLES)) {
                val hits = attempt { api.search(site, query, kind) }
                if (hits == null) {
                    matched = null
                    break
                }
                matched = CommunityExternalMatch.matches(
                    medium, titleKeys, hits, { service }, CommunityExternalHit::id, CommunityExternalHit::format,
                    CommunityExternalHit::titles, fold,
                )
                if (matched.isNotEmpty()) break
            }
            CommunityExternalResolution.record(service, matched, previous, now).also { record ->
                dao.upsertResolution(CommunityResolutionEntity(
                    contentId, service.name, record.status.name, record.remoteId, record.checkedAt, record.failures,
                ))
            }
        }
    }

    private fun CommunityResolutionEntity.toRecord(): CommunityResolutionRecord? = CommunityResolutionRecord(
        service = CommunityExternalService.of(service) ?: return null,
        status = CommunityResolutionStatus.entries.firstOrNull { it.name == status } ?: return null,
        remoteId = remoteId,
        checkedAt = checkedAt,
        failures = failures,
    )

    private fun nowSeconds() = nowMillis() / 1000

    private fun CommunityWorkSource.hint() = CommunitySourceHint(source, url, host)

    companion object {
        /** Android's `CommunityRepository.DEFAULT_RELAYS`; see there for why these and in this order. */
        val DEFAULT_RELAYS = listOf(
            "wss://yabu.me",
            "wss://relay.nostr.wirednet.jp",
            "wss://nos.lol",
            "wss://relay.primal.net",
            "wss://nostr.mom",
            "wss://relay.damus.io",
        )

        const val CHART_LIMIT = 20
        /** How many of the reader's sources [findWork] searches, and how long each may take. */
        private const val FIND_SOURCES = 4
        private const val FIND_TIMEOUT_MILLIS = 8_000L
        private val MEDIA = listOf("comic", "novel", "video")
        private const val DEFAULT_POW_RATING = 16
        private const val DEFAULT_POW_COMMENT = 20
        /** A manifest asking more than this would keep a desktop CPU busy for minutes per comment. */
        private const val MAX_POW = 24
        private const val OUTBOX_BATCH = 20
        private const val SEARCH_TITLES = 2
        private const val COMMENT_LIMIT = 500
        /** Relays cap filter sizes; a work's strong keys and first titles name it well enough. */
        private const val MAX_QUERY_KEYS = 10
        private const val TARGETS_TIMEOUT_MILLIS = 10_000L
        private const val MAX_RETRY_DELAY = 6 * 60 * 60 * 1000L

        /** Two relays holding an event make it safe without waiting for the index. */
        private const val CONFIRMING_RELAYS = 2
        /** How often an event only one relay holds is offered to the others again. */
        private const val CONFIRM_INTERVAL_MILLIS = 60 * 60 * 1000L
        /** A snapshot built this long after the event was published has read it from the relay that has it. */
        private const val INDEX_MARGIN_MILLIS = 10 * 60 * 1000L
        /** The index runs every six hours; three days without one leaves nothing more for the outbox to do. */
        private const val MAX_UNCONFIRMED_MILLIS = 3 * 24 * 60 * 60 * 1000L
        private const val FRESH_TIMEOUT_MILLIS = 5_000L

        /** A minute doubling to six hours: thirty tries span about a week. */
        private const val MAX_ATTEMPTS = 30

        fun retryDelay(attempts: Int): Long = (60_000L shl (attempts - 1).coerceIn(0, 20)).coerceAtMost(MAX_RETRY_DELAY)

        fun shortKey(pubkey: String): String =
            NostrBech32.encodePublicKey(pubkey).let { "${it.take(10)}…${it.takeLast(4)}" }
    }
}
