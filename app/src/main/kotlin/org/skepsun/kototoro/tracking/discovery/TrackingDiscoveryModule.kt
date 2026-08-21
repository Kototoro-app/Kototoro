package org.skepsun.kototoro.tracking.discovery

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import org.skepsun.kototoro.tracking.discovery.data.AppSettingsPreferredTrackingSiteProvider
import org.skepsun.kototoro.tracking.discovery.data.DefaultTrackingSiteDiscoveryService
import org.skepsun.kototoro.tracking.discovery.data.DefaultTrackingSiteMatcher
import org.skepsun.kototoro.tracking.discovery.domain.PreferredTrackingSiteProvider
import org.skepsun.kototoro.tracking.discovery.domain.TrackingSiteDiscoveryService
import org.skepsun.kototoro.tracking.discovery.domain.TrackingSiteMatcher
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
interface TrackingDiscoveryModule {

    @Binds
    @Singleton
    fun bindTrackingSiteDiscoveryService(
        impl: DefaultTrackingSiteDiscoveryService,
    ): TrackingSiteDiscoveryService

    @Binds
    @Singleton
    fun bindPreferredTrackingSiteProvider(
        impl: AppSettingsPreferredTrackingSiteProvider,
    ): PreferredTrackingSiteProvider

    @Binds
    @Singleton
    fun bindTrackingSiteMatcher(
        impl: DefaultTrackingSiteMatcher,
    ): TrackingSiteMatcher
}
