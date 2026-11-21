package org.skepsun.kototoro.suggestions.domain

/**
 * How good a candidate is, as a factor on its score: what makes a suggestion worth reading rather than merely close
 * to the reader's genres. Readers' community ratings say it best once there are enough of them; a source's own rating
 * says something, less reliably and with a smaller pull; knowing neither leaves the score as it was.
 */
object SuggestionQuality {

    const val NEUTRAL = 1.0

    /** Rater weight (see the community index) below which a score is too few people's opinion to act on. */
    const val COMMUNITY_MIN_WEIGHT = 5.0

    /** A community score around here is ordinary and changes nothing. */
    private const val COMMUNITY_PIVOT = 7.0
    private const val COMMUNITY_PER_POINT = 0.2
    private const val COMMUNITY_MIN = 0.5
    private const val COMMUNITY_MAX = 1.6

    /** Readers agree it is poor: below this, with enough of them, it all but leaves the list. */
    private const val COMMUNITY_POOR = 6.0
    private const val COMMUNITY_POOR_FACTOR = 0.4

    /** A source's rating moves a score at most this far either way: sites rate by different scales and crowds. */
    private const val SOURCE_SPREAD = 0.2

    /** [rating] as the source gives it in 0..1; zero or less means the source does not rate. */
    fun fromSourceRating(rating: Float?): Double {
        if (rating == null || rating <= 0f || !rating.isFinite()) return NEUTRAL
        return NEUTRAL + SOURCE_SPREAD * (rating.toDouble().coerceAtMost(1.0) * 2 - 1)
    }

    /** From a community [score] in stars (1..10) carried by rater [weight]; null when there is not enough to say. */
    fun fromCommunity(score: Double?, weight: Double?): Double? {
        if (score == null || !score.isFinite() || weight == null || !weight.isFinite() || weight < COMMUNITY_MIN_WEIGHT) {
            return null
        }
        if (score < COMMUNITY_POOR) return COMMUNITY_POOR_FACTOR
        return (NEUTRAL + COMMUNITY_PER_POINT * (score - COMMUNITY_PIVOT)).coerceIn(COMMUNITY_MIN, COMMUNITY_MAX)
    }

    fun of(sourceRating: Float?, communityScore: Double? = null, communityWeight: Double? = null): Double =
        fromCommunity(communityScore, communityWeight) ?: fromSourceRating(sourceRating)
}

/**
 * Which list the rotation draws today. Stable for a day, so the page is not a different shuffle every time it opens,
 * and different after each refresh the reader asks for, since that is what asking for one means.
 */
fun suggestionRotationSeed(now: Long, refreshAt: Long = 0): Long {
    val day = now / (24L * 60 * 60 * 1000)
    return (day * 1_000_003L) xor refreshAt
}
