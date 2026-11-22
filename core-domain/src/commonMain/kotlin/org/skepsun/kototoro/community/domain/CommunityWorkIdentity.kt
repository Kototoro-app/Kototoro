package org.skepsun.kototoro.community.domain

/** A work as the community knows it: what it is, every key that names it, and whether it is adult. */
data class CommunityWorkIdentity(
    val medium: CommunityMedium,
    val contentType: String,
    val title: String,
    val keys: List<String>,
    val nsfw: Boolean,
    /** Taxonomy tag ids of the work's own tags ([CommunityGenres]); ratings carry them so the index can relate works. */
    val genres: List<String> = emptyList(),
    /**
     * Where the reader read it, published with their ratings and comments when they allow it and the source is one
     * others can have too ([CommunitySources.publishable]); null otherwise.
     */
    val source: CommunityWorkSource? = null,
)

/** Why a rating could not be made; the UI says each differently. */
enum class CommunityRatingRefusal { DISABLED, NO_IDENTITY, NO_KEYS, NSFW }
