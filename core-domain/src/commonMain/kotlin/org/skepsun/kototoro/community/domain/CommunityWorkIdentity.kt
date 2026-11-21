package org.skepsun.kototoro.community.domain

/** A work as the community knows it: what it is, every key that names it, and whether it is adult. */
data class CommunityWorkIdentity(
    val medium: CommunityMedium,
    val contentType: String,
    val title: String,
    val keys: List<String>,
    val nsfw: Boolean,
)

/** Why a rating could not be made; the UI says each differently. */
enum class CommunityRatingRefusal { DISABLED, NO_IDENTITY, NO_KEYS, NSFW }
