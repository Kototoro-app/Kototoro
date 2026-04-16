package org.skepsun.kototoro.discover.bangumidata.domain

import kotlinx.serialization.Serializable

@Serializable
data class BangumiDataRoot(
    val siteMeta: Map<String, BangumiDataSiteMeta>,
    val items: List<BangumiDataItem>
)

@Serializable
data class BangumiDataSiteMeta(
    val title: String,
    val urlTemplate: String,
    val type: String? = null,
    val regions: List<String>? = null
)

@Serializable
data class BangumiDataItem(
    val title: String,
    val titleTranslate: Map<String, List<String>>? = null,
    val type: String,
    val lang: String,
    val officialSite: String? = null,
    val begin: String? = null,
    val end: String? = null,
    val sites: List<BangumiDataSiteLink> = emptyList()
)

@Serializable
data class BangumiDataSiteLink(
    val site: String,
    val id: String,
    val begin: String? = null,
    val broadcast: String? = null
)

data class OfficialSiteDetails(
    val title: String,
    val url: String,
    val type: String?,
    val regions: List<String>
)
