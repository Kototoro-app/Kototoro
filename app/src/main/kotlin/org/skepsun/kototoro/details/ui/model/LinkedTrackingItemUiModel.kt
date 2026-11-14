package org.skepsun.kototoro.details.ui.model

import org.skepsun.kototoro.scrobbling.common.domain.model.ScrobblerService
import org.skepsun.kototoro.scrobbling.common.domain.model.ScrobblingStatus

data class LinkedTrackingItemUiModel(
    val service: ScrobblerService,
    val remoteId: Long,
    val title: String,
    val coverUrl: String?,
    val summary: String?,
    val url: String?,
    val status: ScrobblingStatus?,
    val rating: Float?,
    val hasScrobblingBinding: Boolean,
    val isPreferred: Boolean,
    val communityScore: Float? = null,
    val progress: Int = 0,
)
