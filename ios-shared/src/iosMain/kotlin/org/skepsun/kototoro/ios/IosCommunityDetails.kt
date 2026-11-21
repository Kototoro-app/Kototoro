package org.skepsun.kototoro.ios

import androidx.room.immediateTransaction
import androidx.room.useWriterConnection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.skepsun.kototoro.community.data.CommunityOutboxEntity
import org.skepsun.kototoro.community.data.CommunityRatingEntity
import org.skepsun.kototoro.community.domain.CommunityChartEntry
import org.skepsun.kototoro.community.domain.CommunityCommentFilter
import org.skepsun.kototoro.community.domain.CommunityCommentThreads
import org.skepsun.kototoro.community.domain.CommunityExternalRef
import org.skepsun.kototoro.community.domain.CommunityExternalService
import org.skepsun.kototoro.community.domain.CommunityHighlights
import org.skepsun.kototoro.community.domain.CommunityMedium
import org.skepsun.kototoro.community.domain.CommunityModeration
import org.skepsun.kototoro.community.domain.CommunitySnapshotReader
import org.skepsun.kototoro.community.domain.CommunitySnapshotShards
import org.skepsun.kototoro.community.domain.CommunityWorkKeys
import org.skepsun.kototoro.community.domain.CommunityWorkSummary
import org.skepsun.kototoro.community.nostr.CommunityComment
import org.skepsun.kototoro.community.nostr.CommunityEventSchema
import org.skepsun.kototoro.community.nostr.CommunityProfiles
import org.skepsun.kototoro.community.nostr.CommunityRecentPublications
import org.skepsun.kototoro.community.nostr.NostrBech32
import org.skepsun.kototoro.community.nostr.NostrFilter
import org.skepsun.kototoro.community.nostr.NostrEvent
import org.skepsun.kototoro.community.nostr.NostrKeyPair
import org.skepsun.kototoro.community.nostr.NostrKeys
import org.skepsun.kototoro.community.nostr.NostrPow
import org.skepsun.kototoro.community.nostr.NostrRelayClient
import org.skepsun.kototoro.community.nostr.NostrRelayPool
import org.skepsun.kototoro.community.nostr.NostrUnsignedEvent
import org.skepsun.kototoro.tracking.discovery.PublicTrackingSite

class IosCommunityOverview internal constructor(value: CommunityWorkSummary?) {
    val score: Double? = value?.rating?.score
    val ratingCount: Int = value?.rating?.count ?: 0
    val commentCount: Int = value?.comments ?: 0
    /** Ratings per star, index 0 being one star (1–2 of 10). */
    val starCounts: List<Int> = CommunityHighlights.starBuckets(value?.rating?.distribution.orEmpty())
}

class IosCommunityChartEntry internal constructor(value: CommunityChartEntry) {
    val id: String = value.id
    val title: String = value.title.orEmpty()
    val score: Double = value.score
    val ratingCount: Int = value.count
}

/** The community chart of a window, or while no chart has entries yet ([isFallback]), every rated work. */
class IosCommunityHighlights internal constructor(val entries: List<IosCommunityChartEntry>, val isFallback: Boolean)

class IosCommunityComment internal constructor(
    value: CommunityComment,
    val isReply: Boolean,
    nickname: String?,
    val isPending: Boolean,
) {
    val id: String = value.id
    val author: String = nickname ?: NostrBech32.encodePublicKey(value.pubkey).let { "${it.take(10)}…${it.takeLast(4)}" }
    val content: String = value.content
    val createdAt: Long = value.createdAt
    val spoiler: Boolean = value.spoiler
}

class IosCommunityIdentity private constructor(internal val keys: NostrKeyPair) {
    val publicKey: String get() = NostrBech32.encodePublicKey(keys.publicKeyHex)
    fun recoveryKey(): String = NostrBech32.encodeSecretKey(keys)

    companion object {
        fun create(): IosCommunityIdentity = IosCommunityIdentity(NostrKeyPair.generate())

        fun restore(recoveryKey: String): IosCommunityIdentity? =
            NostrBech32.decode(recoveryKey, "nsec")?.let(NostrKeyPair::fromSecretHex)?.let(::IosCommunityIdentity)
    }
}

class IosCommunityPublication internal constructor(val published: Boolean, val pendingCount: Int)

class IosCommunityDetails internal constructor(
    private val library: IosLibrary,
    private val index: IosCommunityIndex = library.communityIndex,
    private val relayClient: () -> NostrRelayClient = { IosHttp.communityRelays },
) {
    private val publishLock = Mutex()
    private val recent = CommunityRecentPublications()
    private val dao get() = library.database.getCommunityDao()

    val maxCommentLength: Int get() = CommunityEventSchema.MAX_COMMENT_LENGTH

    @Throws(Exception::class)
    suspend fun myRating(content: IosSourceContent): Int = dao.findRating(content.value.id)?.stars ?: 0

    @Throws(Exception::class)
    suspend fun rate(content: IosSourceContent, stars: Int, identity: IosCommunityIdentity): IosCommunityPublication =
        publishLock.withLock {
            val work = requireWork(content)
            val item = content.value
            val medium = CommunityMedium.of(item.source.contentType).wire
            val old = dao.findRating(item.id)
            val time = maxOf(nowMillis() / 1000, (old?.createdAt ?: 0) + 1)
            val address = CommunityEventSchema.ratingAddress(medium, work.keys)
            val rating = sign(CommunityEventSchema.rating(
                work.keys, medium, item.source.contentType, stars, item.title, false, time,
            ), identity, work.index.manifest.powRating)
            val withdrawal = old?.takeIf { it.address != address }?.let {
                sign(CommunityEventSchema.ratingWithdrawal(identity.keys.publicKeyHex, it.address, time), identity)
            }
            library.database.useWriterConnection { writer ->
                writer.immediateTransaction {
                    old?.let { dao.deleteOutbox(it.eventId) }
                    if (withdrawal != null) dao.upsertOutbox(withdrawal.queued())
                    dao.upsertOutbox(rating.queued())
                    dao.upsertRating(CommunityRatingEntity(item.id, stars, address, rating.id, time))
                }
            }
            flush(identity, work.index, rating.id, withdrawal?.id)
        }

    @Throws(Exception::class)
    suspend fun postComment(
        content: IosSourceContent,
        text: String,
        spoiler: Boolean,
        identity: IosCommunityIdentity,
    ): IosCommunityPublication = publishLock.withLock {
        val work = requireWork(content)
        val event = sign(CommunityEventSchema.comment(
            work.keys, text.trim(), content.value.title, nowMillis() / 1000, spoiler = spoiler,
        ), identity, work.index.manifest.powComment)
        dao.upsertOutbox(event.queued())
        flush(identity, work.index, event.id)
    }

    val maxNicknameLength: Int get() = CommunityProfiles.MAX_NAME_LENGTH

    @Throws(Exception::class)
    suspend fun nickname(identity: IosCommunityIdentity): String? {
        val snapshot = requireIndex()
        val pubkey = identity.keys.publicKeyHex
        return profiles(NostrRelayPool(relayClient(), relayUrls(snapshot)), listOf(pubkey))[pubkey]
            ?.let(CommunityProfiles::nickname)
    }

    @Throws(Exception::class)
    suspend fun setNickname(nickname: String, identity: IosCommunityIdentity): IosCommunityPublication = publishLock.withLock {
        val snapshot = requireIndex()
        val pubkey = identity.keys.publicKeyHex
        val pool = NostrRelayPool(relayClient(), relayUrls(snapshot))
        val previous = profiles(pool, listOf(pubkey))[pubkey]
        val event = sign(CommunityProfiles.update(nickname, nowMillis() / 1000, previous), identity)
        library.database.useWriterConnection { writer ->
            writer.immediateTransaction {
                pendingEvents(CommunityProfiles.KIND).filter { it.pubkey == pubkey }.forEach { dao.deleteOutbox(it.id) }
                dao.upsertOutbox(event.queued())
            }
        }
        flush(identity, snapshot, event.id)
    }

    private suspend fun requireIndex() = checkNotNull(index.snapshot()) { localized("社区索引暂不可用") }

    @Throws(Exception::class)
    suspend fun retryPending(identity: IosCommunityIdentity): Int = publishLock.withLock {
        val snapshot = requireIndex()
        flush(identity, snapshot, force = true).pendingCount
    }

    @Throws(Exception::class)
    suspend fun pendingCount(): Int = dao.countOutbox()

    private suspend fun requireWork(content: IosSourceContent): Work =
        checkNotNull(work(content)) { localized("此作品暂不支持社区发布") }

    private suspend fun sign(event: NostrUnsignedEvent, identity: IosCommunityIdentity, difficulty: Int = 0): NostrEvent {
        check(difficulty in 0..24) { localized("社区工作量要求超出设备限制") }
        return checkNotNull(withTimeoutOrNull(60_000L) {
            withContext(Dispatchers.Default) {
                NostrKeys.sign(NostrPow.mine(event, identity.keys.publicKeyHex, difficulty, CommunityEventSchema.POW_TAG),
                    identity.keys)
            }
        }) { localized("生成发布证明超时，请重试") }
    }

    private suspend fun flush(
        identity: IosCommunityIdentity,
        snapshot: IosCommunityIndex.Snapshot,
        target: String? = null,
        relatedEvent: String? = null,
        force: Boolean = false,
    ): IosCommunityPublication {
        val now = nowMillis()
        val relays = relayUrls(snapshot)
        if (relays.isEmpty()) return IosCommunityPublication(false, dao.countOutbox())
        val pool = NostrRelayPool(relayClient(), relays, minAccepted = 1)
        var published = false
        val entries = if (target == null) dao.findDueOutbox(if (force) Long.MAX_VALUE else now, 20) else
            listOfNotNull(relatedEvent, target).mapNotNull { dao.findOutbox(it) }
        for (entry in entries) {
            val event = entry.eventOrNull()
            if (event == null || event.pubkey != identity.keys.publicKeyHex) continue
            val sent = communityAttempt { pool.publish(event, timeoutMillis = 5_000L).isPublished }.getOrDefault(false)
            if (sent) {
                recent.remember(event)
                dao.deleteOutbox(entry.eventId)
                if (entry.eventId == target) published = true
            } else {
                val attempts = entry.attempts + 1
                val delay = (60_000L shl attempts.coerceIn(1, 10)).coerceAtMost(24 * 60 * 60 * 1000L)
                dao.upsertOutbox(entry.copy(attempts = attempts, nextAttemptAt = now + delay))
            }
        }
        return IosCommunityPublication(published, dao.countOutbox())
    }

    private fun CommunityOutboxEntity.eventOrNull(): NostrEvent? =
        runCatching { NostrEvent.fromJson(Json.parseToJsonElement(eventJson).jsonObject) }.getOrNull()
            ?.takeIf(NostrKeys::verify)

    private suspend fun pendingEvents(kind: Int): List<NostrEvent> =
        dao.findOutboxByKind(kind).mapNotNull { it.eventOrNull() }

    private suspend fun profiles(pool: NostrRelayPool, authors: List<String>): Map<String, NostrEvent> {
        if (authors.isEmpty()) return emptyMap()
        val remote = CommunityProfiles.query(pool, authors)
        val pending = pendingEvents(CommunityProfiles.KIND).filter { it.pubkey in authors }
        return CommunityProfiles.latest(remote + pending + recent.profiles(authors))
    }

    private fun NostrEvent.queued() = CommunityOutboxEntity(id, toJson().toString(), kind, createdAt, 0, 0, null)

    private fun nowMillis() = kotlin.time.Clock.System.now().toEpochMilliseconds()

    @Throws(Exception::class)
    suspend fun overview(content: IosSourceContent): IosCommunityOverview? =
        work(content)?.let { IosCommunityOverview(it.summary) }

    /** The best rated works in [window] (`week`, `month`, `all`), as on Android's suggestions page; adult works are left out. */
    @Throws(Exception::class)
    suspend fun highlights(window: String): IosCommunityHighlights {
        val snapshot = index.snapshot() ?: return IosCommunityHighlights(emptyList(), true)
        suspend fun chart(window: String): List<CommunityChartEntry> {
            val paths = MEDIA.map { CommunitySnapshotShards.chartPath(window, it, nsfw = false) }
            val files = snapshot.files(paths)
            return CommunityHighlights.merge(paths.map { path -> files[path]?.let(CommunitySnapshotReader::chart) }, HIGHLIGHTS)
        }
        val popular = chart(window)
        if (popular.isNotEmpty()) return IosCommunityHighlights(popular.map(::IosCommunityChartEntry), false)
        if (window != CommunityHighlights.WINDOW_ALL && chart(CommunityHighlights.WINDOW_ALL).isNotEmpty()) {
            return IosCommunityHighlights(emptyList(), false)
        }
        val paths = CommunityHighlights.worksShardPaths(snapshot.manifest)
        val works = snapshot.files(paths).values.flatMap { CommunitySnapshotReader.works(it).values }
        return IosCommunityHighlights(
            CommunityHighlights.rated(works, allowNsfw = false, limit = HIGHLIGHTS).map(::IosCommunityChartEntry), true,
        )
    }

    @Throws(Exception::class)
    suspend fun comments(content: IosSourceContent): List<IosCommunityComment> {
        val work = work(content) ?: return emptyList()
        val relays = relayUrls(work.index)
        val moderationPath = CommunitySnapshotShards.MODERATION_PATH
        val moderation = if (moderationPath in work.index.manifest.files) {
            val file = checkNotNull(work.index.files(listOf(moderationPath))[moderationPath]) {
                localized("暂时无法读取社区审核规则")
            }
            checkNotNull(CommunitySnapshotReader.moderationOrNull(file)) { localized("暂时无法读取社区审核规则") }
        } else CommunityModeration()
        val pool = NostrRelayPool(relayClient(), relays)
        val result = pool.query(listOf(NostrFilter(
            kinds = listOf(CommunityEventSchema.KIND_COMMENT), tags = mapOf("I" to work.keys), limit = 100,
        )), timeoutMillis = 5_000L)
        val published = result.events.filter { NostrPow.satisfies(it, work.index.manifest.powComment, CommunityEventSchema.POW_TAG) }
            .take(100)
        val pending = pendingEvents(CommunityEventSchema.KIND_COMMENT)
            .filter { event -> event.tagValues("I").any { it in work.keys } }
        val publishedIds = published.mapTo(HashSet(), NostrEvent::id)
        val pendingIds = pending.mapNotNullTo(HashSet()) { it.id.takeUnless(publishedIds::contains) }
        val comments = (published + pending + recent.comments(work.keys)).distinctBy(NostrEvent::id)
            .mapNotNull(CommunityEventSchema::parseComment).filterNot { it.nsfw }
        check(comments.isNotEmpty() || result.relays.any { it.complete }) { localized("社区中继暂不可用") }
        if (comments.isEmpty()) return emptyList()
        val deleted = pool.query(listOf(NostrFilter(
            kinds = listOf(CommunityEventSchema.KIND_DELETION), tags = mapOf("e" to comments.map { it.id }),
        )), timeoutMillis = 5_000L).events.mapNotNull(CommunityEventSchema::parseDeletion)
            .groupBy({ it.pubkey }, { it.eventIds }).mapValues { (_, ids) -> ids.flatten().toSet() }
        val filter = CommunityCommentFilter(
            blockedPubkeys = moderation.blockedPubkeys.toSet(),
            blockedEvents = moderation.blockedEvents.toSet() + work.summary?.hiddenComments.orEmpty(),
            blockedWords = moderation.blockedWords,
        )
        val visible = CommunityCommentThreads.visible(
            comments, filter, deleted, CommunityComment::id, CommunityComment::pubkey, CommunityComment::content,
        )
        val names = profiles(pool, visible.map { it.pubkey }.distinct()).mapValues { CommunityProfiles.nickname(it.value) }
        return CommunityCommentThreads.build(
            visible, CommunityComment::id, CommunityComment::parentId, CommunityComment::createdAt,
        ).flatMap { thread ->
            listOf(thread.root.toDisplayComment(false, names, pendingIds)) +
                thread.replies.map { it.toDisplayComment(true, names, pendingIds) }
        }
    }

    private fun CommunityComment.toDisplayComment(isReply: Boolean, names: Map<String, String?>, pending: Set<String>) =
        IosCommunityComment(this, isReply, names[pubkey], id in pending)

    private data class Work(val keys: List<String>, val summary: CommunityWorkSummary?, val index: IosCommunityIndex.Snapshot)

    private fun relayUrls(snapshot: IosCommunityIndex.Snapshot): List<String> =
        snapshot.manifest.relays.filter { it.startsWith("wss://") }.distinct().take(6)

    private suspend fun work(content: IosSourceContent): Work? {
        val item = content.value
        val medium = CommunityMedium.of(item.source.contentType)
        if (content.isNsfw || medium == CommunityMedium.OTHER) return null
        val tracked = library.tracking.bindings(content).mapNotNull { item ->
            val name = PublicTrackingSite.entries.firstOrNull { it.id == item.service }?.name
            CommunityExternalService.of(name)?.let { CommunityExternalRef(it, item.value.remoteId) }
        }
        val stored = library.database.getCommunityDao().findResolutions(item.id).mapNotNull { entry ->
            if (entry.status != "BOUND") return@mapNotNull null
            val service = CommunityExternalService.of(entry.service) ?: return@mapNotNull null
            CommunityExternalRef(service, entry.remoteId ?: return@mapNotNull null)
        }
        val keys = CommunityWorkKeys.keys(
            medium, listOf(item.title) + item.altTitles, (tracked + stored).distinctBy { it.service }, ::foldCommunityTitle,
        ).take(10)
        if (keys.isEmpty()) return null
        val snapshot = checkNotNull(index.snapshot()) { localized("社区索引暂不可用") }
        val group = snapshot.groups(mapOf(item.id to keys), requireListedFiles = true)[item.id]
        val summary = group?.let {
            checkNotNull(snapshot.works(listOf(it))[it]) { localized("暂时无法读取社区作品数据") }
        }
        return if (summary?.nsfw == true) null else Work(keys, summary, snapshot)
    }

    private companion object {
        val MEDIA = listOf("comic", "novel", "video")
        const val HIGHLIGHTS = 20
    }
}
