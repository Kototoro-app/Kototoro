package org.skepsun.kototoro.tracking.discovery.domain

import kotlinx.coroutines.flow.Flow
import org.skepsun.kototoro.scrobbling.common.domain.model.ScrobblerService

interface PreferredTrackingSiteProvider {

    val preferredSite: Flow<ScrobblerService>

    fun getPreferredSite(): ScrobblerService

    fun setPreferredSite(service: ScrobblerService)
}
