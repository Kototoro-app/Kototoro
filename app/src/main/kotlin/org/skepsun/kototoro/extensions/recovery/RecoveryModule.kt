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
 *
 * NOT provided here: [org.skepsun.kototoro.core.db.dao.SourceOriginsDao] — that binding
 * belongs to the DAO owner (T2B.1), which adds `getSourceOriginsDao()` to the database
 * and exposes it through its own Hilt provider.
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
}
