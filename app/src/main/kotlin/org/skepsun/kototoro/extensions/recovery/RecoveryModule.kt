package org.skepsun.kototoro.extensions.recovery

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Hilt wiring for the source recovery domain layer.
 *
 * Provided here:
 *  - [SourceRuntimeSnapshot]: default no-op until the main session wires the real
 *    Tsundoku/Mihon manager-backed snapshot (Phase 2A+).
 *  - [SourceReferenceProvider]: default no-op until the main session wires the real
 *    content-catalog backed provider.
 *  - [SourceRefreshReporter]: DAO-backed [RoomSourceRefreshReporter] (T3B.4), so repositories
 *    with an `@Inject` constructor can consume the interface directly.
 *
 * NOT provided here: [org.skepsun.kototoro.core.db.dao.SourceOriginsDao] and
 * [org.skepsun.kototoro.core.db.dao.SourceRefreshStateDao] — those bindings belong to the
 * DAO owner (T2B.1), which adds the DAOs to the database and exposes them through its own
 * Hilt providers.
 */
@Module
@InstallIn(SingletonComponent::class)
object RecoveryModule {

    @Provides
    @Singleton
    fun provideSourceRuntimeSnapshot(): SourceRuntimeSnapshot = DefaultSourceRuntimeSnapshot()

    @Provides
    @Singleton
    fun provideSourceReferenceProvider(): SourceReferenceProvider = DefaultSourceReferenceProvider()

    @Provides
    @Singleton
    fun provideSourceRefreshReporter(impl: RoomSourceRefreshReporter): SourceRefreshReporter = impl
}
