package org.skepsun.kototoro.extensions.recovery

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import org.skepsun.kototoro.aniyomi.AniyomiExtensionManager
import org.skepsun.kototoro.extensions.repo.InstalledExtensionSignatureValidator
import org.skepsun.kototoro.ireader.IReaderExtensionManager
import org.skepsun.kototoro.mihon.MihonExtensionManager
import org.skepsun.kototoro.tsundoku.TsundokuExtensionManager

/**
 * Hilt wiring for the source recovery domain layer.
 *
 * Provided here:
 *  - [SourceRuntimeSnapshot]: manager-backed [ManagerBackedSourceRuntimeSnapshot] (T5.1) —
 *    installed lookups derived live from the Mihon / Aniyomi / IReader / Tsundoku managers,
 *    so an origin flips to RESOLVED as soon as its package reloads.
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
    fun provideSourceRuntimeSnapshot(
        mihonManager: MihonExtensionManager,
        aniyomiManager: AniyomiExtensionManager,
        ireaderManager: IReaderExtensionManager,
        tsundokuManager: TsundokuExtensionManager,
        signatureValidator: InstalledExtensionSignatureValidator,
    ): SourceRuntimeSnapshot {
        // Raw upstream sources each expose `.id` (Long/Int, Int-based); `sourceId` args come
        // from the `PREFIX_<id>` key so the string comparison is exact across Long/Int.
        fun <T> resolvePackage(
            installed: List<T>,
            sourceId: String,
            pkg: (T) -> String,
            sources: (T) -> List<*>,
            idOf: (Any?) -> String?,
        ): String? = installed.firstOrNull { loadResult ->
            sources(loadResult).any { source -> idOf(source) == sourceId }
        }?.let(pkg)

        val kanadeId: (Any?) -> String? = { source -> (source as? eu.kanade.tachiyomi.source.Source)?.id?.toString() }
        val animeId: (Any?) -> String? = { source -> (source as? eu.kanade.tachiyomi.animesource.AnimeSource)?.id?.toString() }
        val ireaderId: (Any?) -> String? = { source -> (source as? ireader.core.source.Source)?.id?.toString() }

        return ManagerBackedSourceRuntimeSnapshot(
            mihonPackageFor = { sourceId ->
                resolvePackage(
                    mihonManager.installedExtensions.value,
                    sourceId,
                    pkg = { it.pkgName },
                    sources = { it.sources },
                    idOf = kanadeId,
                )
            },
            aniyomiPackageFor = { sourceId ->
                resolvePackage(
                    aniyomiManager.installedExtensions.value,
                    sourceId,
                    pkg = { it.pkgName },
                    sources = { it.sources },
                    idOf = animeId,
                )
            },
            ireaderPackageFor = { sourceId ->
                resolvePackage(
                    ireaderManager.installedExtensions.value,
                    sourceId,
                    pkg = { it.pkgName },
                    sources = { it.sources },
                    idOf = ireaderId,
                )
            },
            tsundokuPackageFor = { sourceId ->
                resolvePackage(
                    tsundokuManager.installedExtensions.value,
                    sourceId,
                    pkg = { it.pkgName },
                    sources = { it.sources },
                    idOf = kanadeId,
                )
            },
            signatureValidator = signatureValidator,
        )
    }

    @Provides
    @Singleton
    fun provideSourceReferenceProvider(): SourceReferenceProvider = DefaultSourceReferenceProvider()

    @Provides
    @Singleton
    fun provideSourceRefreshReporter(impl: RoomSourceRefreshReporter): SourceRefreshReporter = impl
}
