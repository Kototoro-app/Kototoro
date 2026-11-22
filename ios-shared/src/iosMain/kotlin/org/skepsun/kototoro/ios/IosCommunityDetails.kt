package org.skepsun.kototoro.ios

import org.skepsun.kototoro.community.domain.CommunityChartEntry
import org.skepsun.kototoro.community.domain.CommunityCommentFilter
import org.skepsun.kototoro.community.domain.CommunityHighlights
import org.skepsun.kototoro.community.domain.CommunityMedium
import org.skepsun.kototoro.community.domain.CommunitySnapshotReader
import org.skepsun.kototoro.community.domain.CommunitySnapshotShards
import org.skepsun.kototoro.community.domain.CommunityWorkIdentity
import org.skepsun.kototoro.community.domain.CommunityWorkSummary
import org.skepsun.kototoro.community.nostr.CommunityComment
import org.skepsun.kototoro.community.nostr.CommunityEventSchema
import org.skepsun.kototoro.community.nostr.CommunityProfiles
import org.skepsun.kototoro.community.nostr.NostrBech32
import org.skepsun.kototoro.community.nostr.NostrKeyPair
import org.skepsun.kototoro.community.nostr.NostrRelayClient
import org.skepsun.kototoro.core.library.community.CommunityBlocklist
import org.skepsun.kototoro.core.library.community.CommunityCommentItem
import org.skepsun.kototoro.core.library.community.CommunityIdentityStore
import org.skepsun.kototoro.core.library.community.CommunityService
import org.skepsun.kototoro.core.library.community.CommunitySnapshotCache
import org.skepsun.kototoro.core.library.community.CommunitySnapshots
import org.skepsun.kototoro.core.library.community.CommunitySwitches
import platform.Foundation.NSUserDefaults

class IosCommunityOverview internal constructor(value: CommunityWorkSummary?) {
    /** The mean readers gave; the Bayesian score only orders charts. */
    val score: Double? = value?.rating?.mean
    val ratingCount: Int = value?.rating?.count ?: 0
    val commentCount: Int = value?.comments ?: 0
    /** Ratings per star, index 0 being one star (1–2 of 10). */
    val starCounts: List<Int> = CommunityHighlights.starBuckets(value?.rating?.distribution.orEmpty())
}

class IosCommunityChartEntry internal constructor(value: CommunityChartEntry) {
    val id: String = value.id
    val title: String = value.title.orEmpty()
    val score: Double = value.displayScore
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

/**
 * The community for the SwiftUI screens. Publishing, the outbox, nicknames and comment threads are the shared
 * [CommunityService] (the same code as Android and the desktop); reads of the index stay on [IosCommunityIndex], which
 * keeps its iOS error messages. Swift holds the identity in the Keychain and hands it in with every call that signs.
 * Adult works and works outside comic/novel/video are never published from iOS.
 */
class IosCommunityDetails internal constructor(
    private val library: IosLibrary,
    private val index: IosCommunityIndex = library.communityIndex,
    private val relayClient: () -> NostrRelayClient = { IosHttp.communityRelays },
) {
    private val dao get() = library.database.getCommunityDao()

    /** The identity of the call in progress; Swift owns the Keychain item. */
    private val identities = object : CommunityIdentityStore {
        var current: NostrKeyPair? = null
        override val hasIdentity: Boolean get() = current != null
        override fun keys(): NostrKeyPair? = current
        override fun store(keys: NostrKeyPair) {
            current = keys
        }
        override fun clear() {
            current = null
        }
    }

    private val service by lazy {
        CommunityService(
            database = library.database,
            identities = identities,
            // iOS has no per-reader block list yet; the index's moderation and hidden comments still apply.
            blocklist = object : CommunityBlocklist {
                override val filter = CommunityCommentFilter()
                override fun block(pubkey: String) = Unit
                override fun hide(eventId: String) = Unit
            },
            snapshots = CommunitySnapshots(fetch = index::read, cache = MemorySnapshotCache()),
            relayClient = relayClient(),
            searchApi = null,
            // Swift shows these screens only while the reader has the community on; adult works are refused below.
            // `community_share_sources` is the toggle in the community settings sheet; unset means on.
            switches = {
                val defaults = NSUserDefaults.standardUserDefaults
                CommunitySwitches(
                    enabled = true,
                    shareSources = defaults.objectForKey(SHARE_SOURCES_KEY) == null || defaults.boolForKey(SHARE_SOURCES_KEY),
                )
            },
            fold = ::foldCommunityTitle,
        )
    }

    val maxCommentLength: Int get() = CommunityEventSchema.MAX_COMMENT_LENGTH

    val maxNicknameLength: Int get() = CommunityProfiles.MAX_NAME_LENGTH

    @Throws(Exception::class)
    suspend fun myRating(content: IosSourceContent): Int = dao.findRating(content.value.id)?.stars ?: 0

    @Throws(Exception::class)
    suspend fun rate(content: IosSourceContent, stars: Int, identity: IosCommunityIdentity): IosCommunityPublication {
        val work = requireIdentity(content)
        identities.current = identity.keys
        check(service.rate(content.value.id, work, stars) == null) { localized("此作品暂不支持社区发布") }
        val published = service.ratingEventId(content.value.id)?.let { service.isPublished(it) } ?: false
        return IosCommunityPublication(published, service.pendingCount())
    }

    @Throws(Exception::class)
    suspend fun postComment(
        content: IosSourceContent,
        text: String,
        spoiler: Boolean,
        identity: IosCommunityIdentity,
    ): IosCommunityPublication {
        val work = requireIdentity(content)
        identities.current = identity.keys
        val event = checkNotNull(service.postCommentEvent(work, text.trim(), parent = null, spoiler, language = null)) {
            localized("此作品暂不支持社区发布")
        }
        return IosCommunityPublication(service.isPublished(event.id), service.pendingCount())
    }

    @Throws(Exception::class)
    suspend fun nickname(identity: IosCommunityIdentity): String? {
        identities.current = identity.keys
        return service.nickname()
    }

    @Throws(Exception::class)
    suspend fun setNickname(nickname: String, identity: IosCommunityIdentity): IosCommunityPublication {
        identities.current = identity.keys
        val published = service.setNickname(nickname.trim())
        return IosCommunityPublication(published, service.pendingCount())
    }

    @Throws(Exception::class)
    suspend fun retryPending(identity: IosCommunityIdentity): Int {
        identities.current = identity.keys
        return service.flushOutbox(force = true)
    }

    @Throws(Exception::class)
    suspend fun pendingCount(): Int = service.pendingCount()

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
        // App Review 1.2: no comment is shown without the index's moderation when the index lists it.
        val moderationPath = CommunitySnapshotShards.MODERATION_PATH
        if (moderationPath in work.index.manifest.files) {
            val file = checkNotNull(work.index.files(listOf(moderationPath))[moderationPath]) {
                localized("暂时无法读取社区审核规则")
            }
            checkNotNull(CommunitySnapshotReader.moderationOrNull(file)) { localized("暂时无法读取社区审核规则") }
        }
        val page = service.comments(work.identity, work.summary)
        check(page.threads.isNotEmpty() || page.relaysAnswered > 0) { localized("社区中继暂不可用") }
        return page.threads.flatMap { thread ->
            if (thread.root.comment.nsfw) return@flatMap emptyList()
            listOf(thread.root.toDisplayComment(false)) +
                thread.replies.filterNot { it.comment.nsfw }.map { it.toDisplayComment(true) }
        }
    }

    private fun CommunityCommentItem.toDisplayComment(isReply: Boolean) =
        IosCommunityComment(comment, isReply, author, isPending)

    private class Work(val identity: CommunityWorkIdentity, val summary: CommunityWorkSummary?, val index: IosCommunityIndex.Snapshot)

    private fun publishable(content: IosSourceContent) =
        !content.isNsfw && CommunityMedium.of(content.value.source.contentType) != CommunityMedium.OTHER

    private suspend fun requireIdentity(content: IosSourceContent): CommunityWorkIdentity {
        check(publishable(content)) { localized("此作品暂不支持社区发布") }
        return service.identity(content.value, resolve = false).let { it.copy(keys = it.keys.take(MAX_KEYS)) }
            .also { check(it.keys.isNotEmpty()) { localized("此作品暂不支持社区发布") } }
    }

    private suspend fun work(content: IosSourceContent): Work? {
        if (!publishable(content)) return null
        val identity = service.identity(content.value, resolve = false).let { it.copy(keys = it.keys.take(MAX_KEYS)) }
        if (identity.keys.isEmpty()) return null
        val snapshot = checkNotNull(index.snapshot()) { localized("社区索引暂不可用") }
        val group = snapshot.groups(mapOf(content.value.id to identity.keys), requireListedFiles = true)[content.value.id]
        val summary = group?.let {
            checkNotNull(snapshot.works(listOf(it))[it]) { localized("暂时无法读取社区作品数据") }
        }
        return if (summary?.nsfw == true) null else Work(identity, summary, snapshot)
    }

    /** The service's copy of the index files, for proof-of-work difficulty, relays and moderation. */
    private class MemorySnapshotCache : CommunitySnapshotCache {
        private val files = mutableMapOf<String, Pair<String, String>>()
        override fun read(path: String): String? = files[path]?.first
        override fun readHash(path: String): String? = files[path]?.second
        override fun write(path: String, text: String, hash: String) {
            files[path] = text to hash
        }
        override fun clear() = files.clear()
    }

    private companion object {
        const val SHARE_SOURCES_KEY = "community_share_sources"
        val MEDIA = listOf("comic", "novel", "video")
        const val HIGHLIGHTS = 20
        /** Relays cap filter sizes; a work's strong keys and first titles name it well enough. */
        const val MAX_KEYS = 10
    }
}
