package org.skepsun.kototoro.core.source

import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class SourceRuntimeModule {
    @Binds
    abstract fun bindSourceRuntime(runtime: AndroidSourceRuntime): SourceRuntime

    companion object {
        @Provides
        @Singleton
        fun provideSourceEndpoint(runtime: SourceRuntime): SourceEndpoint = SourceEndpoint(runtime)
    }
}
