package org.skepsun.kototoro.work

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import org.skepsun.kototoro.work.data.DefaultWorkResolver
import org.skepsun.kototoro.work.domain.WorkResolver
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
interface WorkModule {

	@Binds
	@Singleton
	fun bindWorkResolver(
		impl: DefaultWorkResolver,
	): WorkResolver
}
