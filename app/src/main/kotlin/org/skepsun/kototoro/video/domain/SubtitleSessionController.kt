package org.skepsun.kototoro.video.domain

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class SubtitleSessionState(
    val tracks: List<PlaybackSubtitle> = emptyList(),
    val selectedTrackId: String? = null,
    val pendingTrackId: String? = null,
    val error: String? = null,
)

sealed interface SubtitleSelectionResult {
    data object Selected : SubtitleSelectionResult
    data class Failed(val message: String) : SubtitleSelectionResult
}

fun interface SubtitleSelectionExecutor {
    suspend fun select(track: PlaybackSubtitle?): SubtitleSelectionResult
}

interface SubtitleSessionController {
    val state: StateFlow<SubtitleSessionState>

    fun updateExternalTracks(tracks: List<PlaybackSubtitle>)
    fun updateEmbeddedTracks(tracks: List<PlaybackSubtitle>)
    fun updateSelection(trackId: String?)
    suspend fun select(trackId: String?)
}

class DefaultSubtitleSessionController(
    private val selectionExecutor: SubtitleSelectionExecutor,
) : SubtitleSessionController {
    private val externalTracks = LinkedHashMap<String, PlaybackSubtitle>()
    private val embeddedTracks = LinkedHashMap<String, PlaybackSubtitle>()
    private val mutableState = MutableStateFlow(SubtitleSessionState())
    private var selectionGeneration = 0L

    override val state: StateFlow<SubtitleSessionState> = mutableState.asStateFlow()

    override fun updateExternalTracks(tracks: List<PlaybackSubtitle>) {
        externalTracks.replaceWith(tracks.filter { it.origin != SubtitleOrigin.EMBEDDED })
        publishTracks()
    }

    override fun updateEmbeddedTracks(tracks: List<PlaybackSubtitle>) {
        embeddedTracks.replaceWith(tracks.filter { it.origin == SubtitleOrigin.EMBEDDED })
        publishTracks()
    }

    override fun updateSelection(trackId: String?) {
        val current = mutableState.value
        val pendingId = current.pendingTrackId
        mutableState.value = if (pendingId != null && trackId != pendingId) {
            current.copy(selectedTrackId = trackId, error = null)
        } else {
            current.copy(
                selectedTrackId = trackId,
                pendingTrackId = null,
                error = null,
            )
        }
    }

    override suspend fun select(trackId: String?) {
        val track = trackId?.let { id ->
            embeddedTracks[id] ?: externalTracks[id]
        }
        if (trackId != null && track == null) {
            mutableState.value = mutableState.value.copy(
                pendingTrackId = null,
                error = "Subtitle track is no longer available",
            )
            return
        }
        val generation = ++selectionGeneration
        mutableState.value = mutableState.value.copy(
            pendingTrackId = trackId,
            error = null,
        )
        when (val result = selectionExecutor.select(track)) {
            SubtitleSelectionResult.Selected -> {
                if (generation == selectionGeneration) {
                    mutableState.value = mutableState.value.copy(
                        selectedTrackId = trackId,
                        pendingTrackId = null,
                        error = null,
                    )
                }
            }
            is SubtitleSelectionResult.Failed -> {
                if (generation == selectionGeneration) {
                    mutableState.value = mutableState.value.copy(
                        pendingTrackId = null,
                        error = result.message,
                    )
                }
            }
        }
    }

    private fun publishTracks() {
        val tracks = embeddedTracks.values + externalTracks.values
        val selectedId = mutableState.value.selectedTrackId?.takeIf { selected ->
            tracks.any { it.id == selected }
        }
        mutableState.value = mutableState.value.copy(
            tracks = tracks,
            selectedTrackId = selectedId,
        )
    }

    private fun LinkedHashMap<String, PlaybackSubtitle>.replaceWith(tracks: List<PlaybackSubtitle>) {
        clear()
        tracks.forEach { track -> putIfAbsent(track.id, track) }
    }
}
