package org.skepsun.kototoro.core.background

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import org.skepsun.kototoro.core.prefs.AppSettings
import org.skepsun.kototoro.core.prefs.BackgroundArtworkSource
import org.skepsun.kototoro.core.prefs.observeAsFlow
import org.skepsun.kototoro.favourites.domain.FavouritesRepository
import org.skepsun.kototoro.history.data.HistoryRepository
import org.skepsun.kototoro.list.domain.ListSortOrder
import org.skepsun.kototoro.parsers.model.Content
import org.skepsun.kototoro.suggestions.domain.SuggestionRepository
import org.skepsun.kototoro.tracker.domain.TrackingRepository
import javax.inject.Inject
import javax.inject.Singleton

data class BackgroundArtwork(
    val content: Content? = null,
    val imageUri: String? = null,
    val imageOpacity: Float = 1f,
    val overlayStrength: Float = 1f,
    val blurRadius: Float = 35f,
)

@Singleton
class BackgroundArtworkRepository @Inject constructor(
    private val settings: AppSettings,
    private val historyRepository: HistoryRepository,
    private val favouritesRepository: FavouritesRepository,
    private val trackingRepository: TrackingRepository,
    private val suggestionRepository: SuggestionRepository,
) {

    fun observe(): Flow<BackgroundArtwork> {
        return combine(
            observeArtwork(),
            observeAppearance(),
        ) { artwork, appearance ->
            artwork.copy(
                imageOpacity = appearance.imageOpacity,
                overlayStrength = appearance.overlayStrength,
                blurRadius = appearance.blurRadius,
            )
        }.distinctUntilChanged()
    }

    private fun observeArtwork(): Flow<BackgroundArtwork> {
        return observeSelection()
            .flatMapLatest { selection ->
                when (selection.source) {
                    BackgroundArtworkSource.LAST_READ -> settings.observeAsFlow(
                        AppSettings.KEY_HISTORY_EXCLUDE_NSFW,
                    ) { isHistoryExcludeNsfw }
                        .flatMapLatest { excludeNsfw ->
                            historyRepository.observeLast(excludeNsfw = excludeNsfw)
                        }
                        .map(::BackgroundArtwork)

                    BackgroundArtworkSource.LAST_FAVOURITE -> favouritesRepository.observeAll(
                        order = ListSortOrder.NEWEST,
                        filterOptions = emptySet(),
                        limit = 1,
                    ).map { contents ->
                        BackgroundArtwork(content = contents.firstOrNull())
                    }

                    BackgroundArtworkSource.LAST_UPDATED -> trackingRepository.observeUpdatedContent(
                        limit = 1,
                        filterOptions = emptySet(),
                    ).map { contents ->
                        BackgroundArtwork(content = contents.firstOrNull()?.manga)
                    }

                    BackgroundArtworkSource.RANDOM_SUGGESTION -> suggestionRepository.observeAll(
                        limit = RANDOM_SUGGESTION_POOL_SIZE,
                        filterOptions = emptySet(),
                    ).map { contents ->
                        BackgroundArtwork(content = contents.randomOrNull())
                    }

                    BackgroundArtworkSource.CUSTOM -> flowOf(
                        BackgroundArtwork(imageUri = selection.customUri),
                    )
                }
            }
            .distinctUntilChanged()
    }

    private fun observeAppearance(): Flow<BackgroundArtworkAppearance> {
        return combine(
            settings.observeAsFlow(AppSettings.KEY_BACKGROUND_ARTWORK_OPACITY) {
                backgroundArtworkOpacity
            },
            settings.observeAsFlow(AppSettings.KEY_BACKGROUND_ARTWORK_OVERLAY_STRENGTH) {
                backgroundArtworkOverlayStrength
            },
            settings.observeAsFlow(AppSettings.KEY_BACKGROUND_ARTWORK_BLUR) {
                backgroundArtworkBlur
            },
        ) { imageOpacity, overlayStrength, blurRadius ->
            BackgroundArtworkAppearance(
                imageOpacity = imageOpacity / 100f,
                overlayStrength = overlayStrength / 100f,
                blurRadius = blurRadius.toFloat(),
            )
        }.distinctUntilChanged()
    }

    private fun observeSelection(): Flow<Selection> {
        return combine(
            settings.observeAsFlow(AppSettings.KEY_BACKGROUND_ARTWORK_SOURCE) {
                backgroundArtworkSource
            },
            settings.observeAsFlow(AppSettings.KEY_BACKGROUND_ARTWORK_URI) {
                backgroundArtworkUri?.toString()
            },
            settings.observeAsFlow(AppSettings.KEY_SUGGESTIONS) { isSuggestionsEnabled },
        ) { source, customUri, suggestionsEnabled ->
            Selection(
                source = if (source == BackgroundArtworkSource.RANDOM_SUGGESTION && !suggestionsEnabled) {
                    BackgroundArtworkSource.LAST_READ
                } else {
                    source
                },
                customUri = customUri,
            )
        }.distinctUntilChanged()
    }

    private data class Selection(
        val source: BackgroundArtworkSource,
        val customUri: String?,
    )

    private data class BackgroundArtworkAppearance(
        val imageOpacity: Float,
        val overlayStrength: Float,
        val blurRadius: Float,
    )

    private companion object {
        const val RANDOM_SUGGESTION_POOL_SIZE = 64
    }
}
