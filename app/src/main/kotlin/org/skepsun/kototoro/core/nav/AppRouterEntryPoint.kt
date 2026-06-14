package org.skepsun.kototoro.core.nav

import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import org.skepsun.kototoro.core.parser.ContentDataRepository
import org.skepsun.kototoro.core.prefs.AppSettings
import org.skepsun.kototoro.core.parser.ContentRepository
import org.skepsun.kototoro.entitygraph.data.EntityGraphRepository

@EntryPoint
@InstallIn(SingletonComponent::class)
interface AppRouterEntryPoint {

    val settings: AppSettings
    val mangaRepositoryFactory: ContentRepository.Factory
    val entityGraphRepository: EntityGraphRepository
    val jsonSourceManager: org.skepsun.kototoro.core.jsonsource.JsonSourceManager
}
