package org.skepsun.kototoro.tsundoku

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Hilt bindings for the Tsundoku ecosystem (plan Phase 2A).
 *
 * Only binds the injectable APK staging/ClassLoader seam; the loader and manager are
 * provided automatically via their `@Inject` constructors.
 */
@Module
@InstallIn(SingletonComponent::class)
interface TsundokuModule {

    @Binds
    @Singleton
    fun bindTsundokuApkAccessors(impl: DefaultTsundokuApkAccessors): TsundokuApkAccessors
}
