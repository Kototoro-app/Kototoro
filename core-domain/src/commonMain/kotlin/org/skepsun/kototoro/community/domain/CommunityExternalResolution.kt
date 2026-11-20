package org.skepsun.kototoro.community.domain

/** What the last search of one site for one work found (plan §3.5–3.6). */
enum class CommunityResolutionStatus {
    /** Exactly one qualifying hit; its id is kept for good. */
    BOUND,

    /** The site answered and nothing qualified; asked again only after a long while, since sites add works. */
    NONE,

    /** Several hits qualified; same wait as [NONE], a later catalogue may tell them apart. */
    AMBIGUOUS,

    /** The site did not answer (timeout, HTTP error, rate limit). Says nothing about the work; retried soon. */
    FAILED,
}

data class CommunityResolutionRecord(
    val service: CommunityExternalService,
    val status: CommunityResolutionStatus,
    val remoteId: Long?,
    val checkedAt: Long,
    val failures: Int,
)

/**
 * When to search AniList or Bangumi for a work's id. The live evaluation saw Bangumi time out on one search in nine and
 * AniList never fail, so a failure backs off in minutes, while an answer of "nothing" waits weeks.
 */
object CommunityExternalResolution {

    val SEARCHED = listOf(CommunityExternalService.ANILIST, CommunityExternalService.BANGUMI)

    const val MINUTE = 60_000L
    const val DAY = 24 * 60 * MINUTE
    const val RECHECK_NONE = 30 * DAY
    const val MAX_FAILURE_BACKOFF = DAY

    /**
     * Whether [service] should be searched for a work now. Never for [CommunityMedium.OTHER] (neither site lists those),
     * never when the reader's own tracker binding already names the work there, and otherwise by the last [record].
     */
    fun shouldSearch(
        service: CommunityExternalService,
        medium: CommunityMedium,
        trackerRefs: Collection<CommunityExternalRef>,
        record: CommunityResolutionRecord?,
        now: Long,
    ): Boolean {
        if (medium == CommunityMedium.OTHER) return false
        if (trackerRefs.any { it.service == service }) return false
        if (record == null) return true
        return when (record.status) {
            CommunityResolutionStatus.BOUND -> false
            CommunityResolutionStatus.NONE, CommunityResolutionStatus.AMBIGUOUS -> now - record.checkedAt >= RECHECK_NONE
            CommunityResolutionStatus.FAILED -> now - record.checkedAt >= failureBackoff(record.failures)
        }
    }

    /** 5, 10, 20 … minutes after consecutive failures, at most a day. */
    fun failureBackoff(failures: Int): Long {
        val doublings = (failures - 1).coerceIn(0, 10)
        return (5 * MINUTE shl doublings).coerceAtMost(MAX_FAILURE_BACKOFF)
    }

    /** The record after a search: [matched] is every qualifying hit, null when the site did not answer. */
    fun record(
        service: CommunityExternalService,
        matched: List<CommunityExternalRef>?,
        previous: CommunityResolutionRecord?,
        now: Long,
    ): CommunityResolutionRecord = when {
        matched == null -> CommunityResolutionRecord(
            service,
            CommunityResolutionStatus.FAILED,
            remoteId = null,
            checkedAt = now,
            failures = (previous?.takeIf { it.status == CommunityResolutionStatus.FAILED }?.failures ?: 0) + 1,
        )
        matched.size == 1 -> CommunityResolutionRecord(service, CommunityResolutionStatus.BOUND, matched.single().remoteId, now, 0)
        matched.isEmpty() -> CommunityResolutionRecord(service, CommunityResolutionStatus.NONE, null, now, 0)
        else -> CommunityResolutionRecord(service, CommunityResolutionStatus.AMBIGUOUS, null, now, 0)
    }

    /** The ids a work carries: the reader's own bindings first, then what searches bound. */
    fun refs(trackerRefs: Collection<CommunityExternalRef>, records: Collection<CommunityResolutionRecord>): List<CommunityExternalRef> =
        (trackerRefs + records.mapNotNull { record ->
            record.remoteId?.takeIf { record.status == CommunityResolutionStatus.BOUND }?.let { CommunityExternalRef(record.service, it) }
        }).distinctBy { it.service }
}
