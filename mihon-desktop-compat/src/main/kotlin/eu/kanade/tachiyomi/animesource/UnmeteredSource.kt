package eu.kanade.tachiyomi.animesource

/**
 * A source that explicitly states it doesn't require traffic considerations.
 *
 * Usually used for self-hosted sources that don't have rate limits.
 * This is the Aniyomi equivalent of [eu.kanade.tachiyomi.source.UnmeteredSource].
 */
interface UnmeteredSource
