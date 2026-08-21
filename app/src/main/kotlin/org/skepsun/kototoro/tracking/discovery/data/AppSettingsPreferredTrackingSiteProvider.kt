package org.skepsun.kototoro.tracking.discovery.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.skepsun.kototoro.core.prefs.AppSettings
import org.skepsun.kototoro.scrobbling.common.domain.model.ScrobblerService
import org.skepsun.kototoro.tracking.discovery.domain.PreferredTrackingSiteProvider
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AppSettingsPreferredTrackingSiteProvider @Inject constructor(
    private val settings: AppSettings,
) : PreferredTrackingSiteProvider {

    override val preferredSite: Flow<ScrobblerService> = settings.observe(AppSettings.KEY_PREFERRED_TRACKING_SITE)
        .map { settings.preferredTrackingSite }

    override fun getPreferredSite(): ScrobblerService = settings.preferredTrackingSite

    override fun setPreferredSite(service: ScrobblerService) {
        settings.preferredTrackingSite = service
    }
}
