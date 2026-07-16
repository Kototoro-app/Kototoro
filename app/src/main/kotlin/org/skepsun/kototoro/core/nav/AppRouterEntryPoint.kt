package org.skepsun.kototoro.core.nav

import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import org.skepsun.kototoro.core.parser.ContentDataRepository
import org.skepsun.kototoro.core.prefs.AppSettings
import org.skepsun.kototoro.core.parser.ContentRepository
import org.skepsun.kototoro.work.domain.WorkResolver
import org.skepsun.kototoro.space.domain.SpaceFeatureFlagsRepository
import org.skepsun.kototoro.space.domain.SpaceRepository

@EntryPoint
@InstallIn(SingletonComponent::class)
interface AppRouterEntryPoint {

    val settings: AppSettings
    val contentDataRepository: ContentDataRepository
    val mangaRepositoryFactory: ContentRepository.Factory
    val workResolver: WorkResolver
    val jsonSourceManager: org.skepsun.kototoro.core.jsonsource.JsonSourceManager
    val spaceFeatureFlagsRepository: SpaceFeatureFlagsRepository
    val spaceRepository: SpaceRepository
}
