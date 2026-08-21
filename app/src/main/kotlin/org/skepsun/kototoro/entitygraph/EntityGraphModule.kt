package org.skepsun.kototoro.entitygraph

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import org.skepsun.kototoro.entitygraph.data.DefaultEntityBindingMatcher
import org.skepsun.kototoro.entitygraph.data.DefaultEntityGraphSourceAdapter
import org.skepsun.kototoro.entitygraph.domain.EntityBindingMatcher
import org.skepsun.kototoro.entitygraph.domain.EntityGraphSourceAdapter
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
interface EntityGraphModule {

    @Binds
    @Singleton
    fun bindEntityBindingMatcher(
        impl: DefaultEntityBindingMatcher,
    ): EntityBindingMatcher

    @Binds
    @Singleton
    fun bindEntityGraphSourceAdapter(
        impl: DefaultEntityGraphSourceAdapter,
    ): EntityGraphSourceAdapter
}
