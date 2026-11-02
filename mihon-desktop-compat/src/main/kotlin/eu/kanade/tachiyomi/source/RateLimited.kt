package eu.kanade.tachiyomi.source

/**
 * Tsundoku source-api (extensions-lib 1.6) request-pacing declaration, missing from the Suwayomi API like
 * [SourceTracker]; NovelSourcery's novelfire, ranobes and syosetu extensions implement it. The hosts do not read
 * the values yet; the Android copy in `app` seeds its per-extension rate limits from them.
 */
interface RateLimited {

    val minimumDelayMillis: Long

    val recommendedDelayMillis: Long
        get() = minimumDelayMillis

    val recommendedPermits: Int
        get() = 1
}
