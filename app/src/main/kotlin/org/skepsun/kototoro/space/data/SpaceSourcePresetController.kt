package org.skepsun.kototoro.space.data

import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.skepsun.kototoro.core.db.MangaDatabase
import org.skepsun.kototoro.core.prefs.AppSettings
import org.skepsun.kototoro.core.prefs.observeAsFlow
import org.skepsun.kototoro.core.util.ext.processLifecycleScope
import org.skepsun.kototoro.space.domain.SpaceFeatureFlagsRepository
import org.skepsun.kototoro.space.domain.SpaceId
import org.skepsun.kototoro.space.domain.SpaceRepository

@Singleton
class SpaceSourcePresetController @Inject constructor(
    private val database: MangaDatabase,
    private val spaceRepository: SpaceRepository,
    private val featureFlagsRepository: SpaceFeatureFlagsRepository,
    private val settings: AppSettings,
) {
    private val started = AtomicBoolean(false)
    private val mutex = Mutex()
    private val currentSpace = MutableStateFlow<SpaceId?>(null)
    private val applying = MutableStateFlow(false)
    private var globalPresetId = settings.activeSourcePresetId
    private var jobs: List<Job> = emptyList()

    fun start() {
        if (!settings.isEntitySpaceEnabled) return
        if (!started.compareAndSet(false, true)) return
        jobs = listOf(
            processLifecycleScope.launch(Dispatchers.Default) {
                combine(
                    spaceRepository.activeSpace,
                    featureFlagsRepository.flags,
                ) { spaceId, flags -> spaceId.takeIf { flags.entitySpaceEnabled } }
                    .distinctUntilChanged()
                    .collect(::activate)
            },
            processLifecycleScope.launch(Dispatchers.Default) {
                combine(
                    settings.observeAsFlow(AppSettings.KEY_ACTIVE_SOURCE_PRESET_ID) { activeSourcePresetId },
                    currentSpace,
                    applying,
                ) { presetId, spaceId, isApplying -> Triple(presetId, spaceId, isApplying) }
                    .distinctUntilChanged()
                    .collect { (presetId, spaceId, isApplying) ->
                        if (isApplying) return@collect
                        if (spaceId == null) {
                            globalPresetId = presetId
                        } else {
                            save(spaceId, presetId)
                        }
                    }
            },
        )
    }

    fun stop() {
        if (!started.compareAndSet(true, false)) return
        jobs.forEach(Job::cancel)
        jobs = emptyList()
        settings.activeSourcePresetId = globalPresetId
        currentSpace.value = null
        applying.value = false
    }

    private suspend fun activate(spaceId: SpaceId?) {
        mutex.withLock {
            applying.value = true
            try {
                if (spaceId == null) {
                    currentSpace.value = null
                    settings.activeSourcePresetId = globalPresetId
                    return
                }
                val stored = database.getSpaceRoutePreferencesDao().find(spaceId.value, SOURCE_PRESET_ROUTE_KEY)
                    ?.takeIf { it.schemaVersion == SOURCE_PRESET_SCHEMA_VERSION }
                    ?.payload
                    ?.toLongOrNull()
                val resolved = stored
                    ?.takeIf { it == NO_PRESET || database.getSourcePresetsDao().find(it) != null }
                    ?: globalPresetId
                currentSpace.value = spaceId
                settings.activeSourcePresetId = resolved
                if (stored != resolved) save(spaceId, resolved)
            } finally {
                applying.value = false
            }
        }
    }

    private suspend fun save(spaceId: SpaceId, presetId: Long) {
        database.getSpaceRoutePreferencesDao().upsert(
            SpaceRoutePreferencesEntity(
                spaceId = spaceId.value,
                routeKey = SOURCE_PRESET_ROUTE_KEY,
                payload = presetId.toString(),
                schemaVersion = SOURCE_PRESET_SCHEMA_VERSION,
                updatedAt = System.currentTimeMillis(),
            ),
        )
    }

    private companion object {
        const val SOURCE_PRESET_ROUTE_KEY = "space:source_preset"
        const val SOURCE_PRESET_SCHEMA_VERSION = 1
        const val NO_PRESET = -1L
    }
}
