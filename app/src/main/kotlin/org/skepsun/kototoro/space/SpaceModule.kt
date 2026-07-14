package org.skepsun.kototoro.space

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import org.skepsun.kototoro.space.domain.DefaultSpaceContentPolicy
import org.skepsun.kototoro.space.domain.SpaceContentPolicy

@Module
@InstallIn(SingletonComponent::class)
interface SpaceModule {

	@Binds
	fun bindSpaceContentPolicy(impl: DefaultSpaceContentPolicy): SpaceContentPolicy
}
