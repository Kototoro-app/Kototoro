package com.lagradost.cloudstream3.ui.result

import com.lagradost.cloudstream3.APIHolder.getApiFromNameNull
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.Score
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.utils.ExtractorLink

enum class VideoWatchState {
	/** Default value when no key is set */
	None,
	Watched,
}

/** An episode as Cloudstream's details screen lists it. */
data class ResultEpisode(
	val headerName: String,
	val name: String?,
	val poster: String?,
	val episode: Int,
	val seasonIndex: Int?,
	val season: Int?,
	val data: String,
	val apiName: String,
	val id: Int,
	val index: Int,
	val position: Long,
	val duration: Long,
	val score: Score?,
	val description: String?,
	val isFiller: Boolean?,
	val tvType: TvType,
	val parentId: Int,
	val videoWatchState: VideoWatchState,
	val totalEpisodeIndex: Int? = null,
	val airDate: Long? = null,
	val runTime: Int? = null,
)

/** Links found for an episode; subtitles are Cloudstream player objects, which Kototoro does not have. */
data class LinkLoadingResult(
	val links: List<ExtractorLink>,
	val subs: List<Any>,
	val syncData: HashMap<String, String>,
)

/** The id Cloudstream keeps a title's state under. */
fun LoadResponse.getId(): Int =
	uniqueUrl.replace(getApiFromNameNull(apiName)?.mainUrl.orEmpty(), "").replace("/", "").hashCode()
