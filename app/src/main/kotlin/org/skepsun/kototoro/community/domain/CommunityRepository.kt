package org.skepsun.kototoro.community.domain

import kotlinx.coroutines.flow.Flow
import org.skepsun.kototoro.community.data.CommunityOutboxWorker
import org.skepsun.kototoro.community.nostr.NostrKeyPair
import org.skepsun.kototoro.core.library.community.CommunityFreshRatings
import org.skepsun.kototoro.core.library.community.CommunityService
import org.skepsun.kototoro.core.prefs.AppSettings
import org.skepsun.kototoro.core.source.toSourceContent
import org.skepsun.kototoro.core.util.ChineseConverter
import org.skepsun.kototoro.parsers.model.Content
import java.text.Normalizer
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The reader's side of community ratings (plan §7) for Android's screens: the shared [CommunityService] over parser
 * [Content], plus the WorkManager job that keeps retrying the outbox once the device is online.
 */
@Singleton
class CommunityRepository @Inject constructor(
    private val settings: AppSettings,
    private val service: CommunityService,
    private val outboxScheduler: CommunityOutboxWorker.Scheduler,
) {

    val isEnabled: Boolean get() = settings.isCommunityEnabled

    fun observeMyRating(mangaId: Long): Flow<Int?> = service.observeMyRating(mangaId)

    /**
     * The keys [content] is published under. With [resolve], AniList and Bangumi are searched when their policy says so
     * (one search per site per work, retried only after failures); without it, only what is already known is used.
     */
    suspend fun identity(content: Content, trackerRefs: List<CommunityExternalRef>, resolve: Boolean): CommunityWorkIdentity =
        service.identity(content.toSourceContent(), resolve, trackerRefs)

    suspend fun summary(identity: CommunityWorkIdentity): CommunityWorkSummary? = service.summary(identity)

    suspend fun freshRatings(mangaId: Long, identity: CommunityWorkIdentity): CommunityFreshRatings =
        service.freshRatings(mangaId, identity)

    fun refusal(identity: CommunityWorkIdentity): CommunityRatingRefusal? = service.refusal(identity)

    /** Rates the work [stars] of 10 and queues the event; returns the refusal instead when it may not be rated. */
    suspend fun rate(mangaId: Long, identity: CommunityWorkIdentity, stars: Int): CommunityRatingRefusal? =
        service.rate(mangaId, identity, stars).also { scheduleLeftovers() }

    /** Takes the reader's rating back, everywhere it was published. */
    suspend fun withdraw(mangaId: Long) {
        service.withdraw(mangaId)
        scheduleLeftovers()
    }

    /**
     * Publishes what is due in the outbox and returns how many events no relay has taken yet. Anything left gets the
     * background worker, which keeps trying once the device is online.
     */
    suspend fun flushOutbox(): Int = service.flushOutbox().also { left -> if (left > 0) outboxScheduler.enqueue() }

    suspend fun nickname(keys: NostrKeyPair): String? = service.nickname()

    /** Publishes [nickname]; true when a relay stored it, false when it waits in the outbox. */
    suspend fun setNickname(nickname: String, keys: NostrKeyPair): Boolean =
        service.setNickname(nickname).also { scheduleLeftovers() }

    /** Comments, deletions and reports leave through the same outbox; the worker picks up what they left. */
    internal suspend fun scheduleLeftovers() {
        if (service.pendingCount() > 0) outboxScheduler.enqueue()
    }

    companion object {

        /** The platform fold of plan §3.2: NFKC, traditional to simplified Chinese, then invariant lowercase. */
        fun fold(text: String): String =
            ChineseConverter.t2s(Normalizer.normalize(text, Normalizer.Form.NFKC)).lowercase(Locale.ROOT)
    }
}
