package org.skepsun.kototoro.space

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import org.skepsun.kototoro.space.domain.DefaultSpaceContentPolicy
import org.skepsun.kototoro.space.data.AppSettingsSpaceLocalDataSource
import org.skepsun.kototoro.space.data.DefaultSpaceFeatureFlagsRepository
import org.skepsun.kototoro.space.data.DefaultSpaceRepository
import org.skepsun.kototoro.space.data.LogcatSpaceDiagnostics
import org.skepsun.kototoro.space.data.SpaceDiagnostics
import org.skepsun.kototoro.space.data.SpaceLocalDataSource
import org.skepsun.kototoro.space.domain.SpaceContentPolicy
import org.skepsun.kototoro.space.domain.SpaceFeatureFlagsRepository
import org.skepsun.kototoro.space.domain.SpaceRepository

@Module
@InstallIn(SingletonComponent::class)
interface SpaceModule {

	@Binds
	fun bindSpaceContentPolicy(impl: DefaultSpaceContentPolicy): SpaceContentPolicy

	@Binds
	fun bindSpaceRepository(impl: DefaultSpaceRepository): SpaceRepository

	@Binds
	fun bindSpaceLocalDataSource(impl: AppSettingsSpaceLocalDataSource): SpaceLocalDataSource

	@Binds
	fun bindSpaceDiagnostics(impl: LogcatSpaceDiagnostics): SpaceDiagnostics

	@Binds
	fun bindSpaceFeatureFlagsRepository(impl: DefaultSpaceFeatureFlagsRepository): SpaceFeatureFlagsRepository
}
