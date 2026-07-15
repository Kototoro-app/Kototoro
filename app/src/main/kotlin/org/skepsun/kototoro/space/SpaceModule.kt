package org.skepsun.kototoro.space

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import org.skepsun.kototoro.space.domain.DefaultSpaceContentPolicy
import org.skepsun.kototoro.space.data.AppSettingsSpaceLocalDataSource
import org.skepsun.kototoro.space.data.DefaultSpaceFeatureFlagsRepository
import org.skepsun.kototoro.space.data.DefaultSpaceRepository
import org.skepsun.kototoro.space.data.DefaultSpaceRoutePreferencesRepository
import org.skepsun.kototoro.space.data.DefaultSpaceSessionRepository
import org.skepsun.kototoro.space.data.DefaultSpaceSessionValidator
import org.skepsun.kototoro.space.data.DefaultSpaceSourceAvailability
import org.skepsun.kototoro.space.data.DefaultSpaceSwitchCoordinator
import org.skepsun.kototoro.space.data.DefaultSpaceCatalogRepository
import org.skepsun.kototoro.space.data.LogcatSpaceDiagnostics
import org.skepsun.kototoro.space.data.SpaceDiagnostics
import org.skepsun.kototoro.space.data.SpaceLocalDataSource
import org.skepsun.kototoro.space.domain.SpaceContentPolicy
import org.skepsun.kototoro.space.domain.SpaceFeatureFlagsRepository
import org.skepsun.kototoro.space.domain.SpaceRepository
import org.skepsun.kototoro.space.domain.SpaceRoutePreferencesRepository
import org.skepsun.kototoro.space.domain.SpaceSessionRepository
import org.skepsun.kototoro.space.domain.SpaceSessionValidator
import org.skepsun.kototoro.space.domain.SpaceSourceAvailability
import org.skepsun.kototoro.space.domain.SpaceSwitchCoordinator
import org.skepsun.kototoro.space.domain.SpaceCatalogRepository

@Module
@InstallIn(SingletonComponent::class)
interface SpaceModule {

	@Binds
	fun bindSpaceContentPolicy(impl: DefaultSpaceContentPolicy): SpaceContentPolicy

	@Binds
	fun bindSpaceCatalogRepository(impl: DefaultSpaceCatalogRepository): SpaceCatalogRepository

	@Binds
	fun bindSpaceRepository(impl: DefaultSpaceRepository): SpaceRepository

	@Binds
	fun bindSpaceRoutePreferencesRepository(
		impl: DefaultSpaceRoutePreferencesRepository,
	): SpaceRoutePreferencesRepository

	@Binds
	fun bindSpaceLocalDataSource(impl: AppSettingsSpaceLocalDataSource): SpaceLocalDataSource

	@Binds
	fun bindSpaceDiagnostics(impl: LogcatSpaceDiagnostics): SpaceDiagnostics

	@Binds
	fun bindSpaceFeatureFlagsRepository(impl: DefaultSpaceFeatureFlagsRepository): SpaceFeatureFlagsRepository

	@Binds
	fun bindSpaceSessionRepository(impl: DefaultSpaceSessionRepository): SpaceSessionRepository

	@Binds
	fun bindSpaceSessionValidator(impl: DefaultSpaceSessionValidator): SpaceSessionValidator

	@Binds
	fun bindSpaceSourceAvailability(impl: DefaultSpaceSourceAvailability): SpaceSourceAvailability

	@Binds
	fun bindSpaceSwitchCoordinator(impl: DefaultSpaceSwitchCoordinator): SpaceSwitchCoordinator
}
