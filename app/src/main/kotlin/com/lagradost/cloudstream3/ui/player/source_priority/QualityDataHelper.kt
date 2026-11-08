package com.lagradost.cloudstream3.ui.player.source_priority

import com.lagradost.cloudstream3.utils.Qualities

/**
 * Cloudstream's per-profile source and quality priorities, which link-sorting plugins (Ultima) read. Kototoro picks
 * links its own way, so every priority is neutral and nothing is kept.
 */
object QualityDataHelper {
	data class QualityProfile(
		val id: Int,
		val name: String? = null,
	)

	fun getProfiles(): List<QualityProfile> = listOf(QualityProfile(id = 0))

	fun getSourcePriority(profile: Int, name: String?): Int = 0

	fun setSourcePriority(profile: Int, name: String, priority: Int) = Unit

	fun getQualityPriority(profile: Int, quality: Qualities): Int = 0
}
