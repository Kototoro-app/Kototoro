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
import org.skepsun.kototoro.community.domain.CommunityCommentFilter
import org.skepsun.kototoro.community.domain.CommunityCommentThreads
import org.skepsun.kototoro.community.domain.CommunityExternalRef
import org.skepsun.kototoro.community.domain.CommunityExternalService
import org.skepsun.kototoro.community.domain.CommunityMedium
import org.skepsun.kototoro.community.domain.CommunityModeration
import org.skepsun.kototoro.community.domain.CommunitySnapshotReader
import org.skepsun.kototoro.community.domain.CommunitySnapshotShards
import org.skepsun.kototoro.community.domain.CommunityWorkKeys
import org.skepsun.kototoro.community.domain.CommunityWorkSummary
import org.skepsun.kototoro.community.nostr.CommunityComment
import org.skepsun.kototoro.community.nostr.CommunityEventSchema
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
}

class IosCommunityComment internal constructor(value: CommunityComment, val isReply: Boolean) {
    val id: String = value.id
    val author: String = NostrBech32.encodePublicKey(value.pubkey).let { "${it.take(10)}…${it.takeLast(4)}" }
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

    @Throws(Exception::class)
    suspend fun retryPending(identity: IosCommunityIdentity): Int = publishLock.withLock {
        val snapshot = checkNotNull(index.snapshot()) { localized("社区索引暂不可用") }
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
            val event = runCatching { NostrEvent.fromJson(Json.parseToJsonElement(entry.eventJson).jsonObject) }.getOrNull()
            if (event == null || event.pubkey != identity.keys.publicKeyHex || !NostrKeys.verify(event)) continue
            val sent = communityAttempt { pool.publish(event, timeoutMillis = 5_000L).isPublished }.getOrDefault(false)
            if (sent) {
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

    private fun NostrEvent.queued() = CommunityOutboxEntity(id, toJson().toString(), kind, createdAt, 0, 0, null)

    private fun nowMillis() = kotlin.time.Clock.System.now().toEpochMilliseconds()

    @Throws(Exception::class)
    suspend fun overview(content: IosSourceContent): IosCommunityOverview? =
        work(content)?.let { IosCommunityOverview(it.summary) }

    @Throws(Exception::class)
    suspend fun comments(content: IosSourceContent): List<IosCommunityComment> {
        val work = work(content) ?: return emptyList()
        val relays = relayUrls(work.index)
        check(relays.isNotEmpty()) { localized("社区中继暂不可用") }
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
            .mapNotNull(CommunityEventSchema::parseComment).filterNot { it.nsfw }.take(100)
        check(published.isNotEmpty() || result.relays.any { it.complete }) { localized("社区中继暂不可用") }
        if (published.isEmpty()) return emptyList()
        val deleted = pool.query(listOf(NostrFilter(
            kinds = listOf(CommunityEventSchema.KIND_DELETION), tags = mapOf("e" to published.map { it.id }),
        )), timeoutMillis = 5_000L).events.mapNotNull(CommunityEventSchema::parseDeletion)
            .groupBy({ it.pubkey }, { it.eventIds }).mapValues { (_, ids) -> ids.flatten().toSet() }
        val filter = CommunityCommentFilter(
            blockedPubkeys = moderation.blockedPubkeys.toSet(),
            blockedEvents = moderation.blockedEvents.toSet() + work.summary?.hiddenComments.orEmpty(),
            blockedWords = moderation.blockedWords,
        )
        val visible = CommunityCommentThreads.visible(
            published, filter, deleted, CommunityComment::id, CommunityComment::pubkey, CommunityComment::content,
        )
        return CommunityCommentThreads.build(
            visible, CommunityComment::id, CommunityComment::parentId, CommunityComment::createdAt,
        ).flatMap { thread -> listOf(IosCommunityComment(thread.root, false)) + thread.replies.map {
            IosCommunityComment(it, true)
        } }
    }

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
}
