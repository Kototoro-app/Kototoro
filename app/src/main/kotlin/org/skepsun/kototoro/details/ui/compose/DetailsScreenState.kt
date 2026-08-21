package org.skepsun.kototoro.details.ui.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import org.skepsun.kototoro.entitygraph.ui.details.EntityRelationItem
import org.skepsun.kototoro.parsers.model.ContentTag

/**
 * State holder for [DetailsScreenContent]'s local UI state: dialog/sheet
 * visibility, pending searches, supplemental-relation selection and the
 * header/dock anchor measurements. Created by [rememberDetailsScreenState]
 * so the remember/rememberSaveable semantics are preserved while the
 * declarations move out of the huge assembler composable.
 *
 * Callers read the exposed [State]s and mutate through the setter methods,
 * keeping the MutableState snapshots private to the holder.
 */
@Stable
internal class DetailsScreenState internal constructor(
    private val _showDeleteLocalDialog: MutableState<Boolean>,
    private val _showShareOptions: MutableState<Boolean>,
    private val _pendingAuthorSearch: MutableState<PendingAuthorSearch?>,
    private val _pendingTagSearch: MutableState<ContentTag?>,
    private val _showFavoriteDialog: MutableState<Boolean>,
    private val _showDownloadDialog: MutableState<Boolean>,
    private val _showReadingRecordSheet: MutableState<Boolean>,
    private val _showCommentsDialog: MutableState<Boolean>,
    private val _showReviewsDialog: MutableState<Boolean>,
    private val _selectedSupplementalRelationItem: MutableState<EntityRelationItem?>,
    private val _showMetadataSourceDialog: MutableState<Boolean>,
    private val _showReadingSourceDialog: MutableState<Boolean>,
    private val _isModernDockCompact: MutableState<Boolean>,
    private val _toolbarBottomPx: MutableState<Float>,
    private val _lastToolbarBottomPx: MutableState<Float>,
    private val _infoCardTopPx: MutableState<Float>,
    private val _infoCardMidPx: MutableState<Float>,
    private val _initialInfoCardTopPx: MutableState<Float>,
    private val _initialInfoCardMidPx: MutableState<Float>,
) {
    val showDeleteLocalDialog: State<Boolean> = _showDeleteLocalDialog
    val showShareOptions: State<Boolean> = _showShareOptions
    val pendingAuthorSearch: State<PendingAuthorSearch?> = _pendingAuthorSearch
    val pendingTagSearch: State<ContentTag?> = _pendingTagSearch
    val showFavoriteDialog: State<Boolean> = _showFavoriteDialog
    val showDownloadDialog: State<Boolean> = _showDownloadDialog
    val showReadingRecordSheet: State<Boolean> = _showReadingRecordSheet
    val showCommentsDialog: State<Boolean> = _showCommentsDialog
    val showReviewsDialog: State<Boolean> = _showReviewsDialog
    val selectedSupplementalRelationItem: State<EntityRelationItem?> = _selectedSupplementalRelationItem
    val showMetadataSourceDialog: State<Boolean> = _showMetadataSourceDialog
    val showReadingSourceDialog: State<Boolean> = _showReadingSourceDialog
    val isModernDockCompact: State<Boolean> = _isModernDockCompact
    val toolbarBottomPx: State<Float> = _toolbarBottomPx
    val lastToolbarBottomPx: State<Float> = _lastToolbarBottomPx
    val infoCardTopPx: State<Float> = _infoCardTopPx
    val infoCardMidPx: State<Float> = _infoCardMidPx
    val initialInfoCardTopPx: State<Float> = _initialInfoCardTopPx
    val initialInfoCardMidPx: State<Float> = _initialInfoCardMidPx

    fun setShowDeleteLocalDialog(value: Boolean) {
        _showDeleteLocalDialog.value = value
    }

    fun setShowShareOptions(value: Boolean) {
        _showShareOptions.value = value
    }

    fun setPendingAuthorSearch(value: PendingAuthorSearch?) {
        _pendingAuthorSearch.value = value
    }

    fun setPendingTagSearch(value: ContentTag?) {
        _pendingTagSearch.value = value
    }

    fun setShowFavoriteDialog(value: Boolean) {
        _showFavoriteDialog.value = value
    }

    fun setShowDownloadDialog(value: Boolean) {
        _showDownloadDialog.value = value
    }

    fun setShowReadingRecordSheet(value: Boolean) {
        _showReadingRecordSheet.value = value
    }

    fun setShowCommentsDialog(value: Boolean) {
        _showCommentsDialog.value = value
    }

    fun setShowReviewsDialog(value: Boolean) {
        _showReviewsDialog.value = value
    }

    fun setSelectedSupplementalRelationItem(value: EntityRelationItem?) {
        _selectedSupplementalRelationItem.value = value
    }

    fun setShowMetadataSourceDialog(value: Boolean) {
        _showMetadataSourceDialog.value = value
    }

    fun setShowReadingSourceDialog(value: Boolean) {
        _showReadingSourceDialog.value = value
    }

    fun setIsModernDockCompact(value: Boolean) {
        _isModernDockCompact.value = value
    }

    fun setToolbarBottomPx(value: Float) {
        _toolbarBottomPx.value = value
    }

    fun setLastToolbarBottomPx(value: Float) {
        _lastToolbarBottomPx.value = value
    }

    fun setInfoCardTopPx(value: Float) {
        _infoCardTopPx.value = value
    }

    fun setInfoCardMidPx(value: Float) {
        _infoCardMidPx.value = value
    }

    fun setInitialInfoCardTopPx(value: Float) {
        _initialInfoCardTopPx.value = value
    }

    fun setInitialInfoCardMidPx(value: Float) {
        _initialInfoCardMidPx.value = value
    }

}

@Composable
internal fun rememberDetailsScreenState(): DetailsScreenState {
    val showDeleteLocalDialog = remember { mutableStateOf(false) }
    val showShareOptions = remember { mutableStateOf(false) }
    val pendingAuthorSearch = remember { mutableStateOf<PendingAuthorSearch?>(null) }
    val pendingTagSearch = remember { mutableStateOf<ContentTag?>(null) }
    val showFavoriteDialog = remember { mutableStateOf(false) }
    val showDownloadDialog = remember { mutableStateOf(false) }
    val showReadingRecordSheet = remember { mutableStateOf(false) }
    val showCommentsDialog = remember { mutableStateOf(false) }
    val showReviewsDialog = remember { mutableStateOf(false) }
    val selectedSupplementalRelationItem = remember { mutableStateOf<EntityRelationItem?>(null) }
    val showMetadataSourceDialog = rememberSaveable { mutableStateOf(false) }
    val showReadingSourceDialog = rememberSaveable { mutableStateOf(false) }
    val isModernDockCompact = rememberSaveable { mutableStateOf(false) }
    val toolbarBottomPx = remember { mutableFloatStateOf(Float.NaN) }
    val lastToolbarBottomPx = remember { mutableFloatStateOf(Float.NaN) }
    val infoCardTopPx = remember { mutableFloatStateOf(Float.NaN) }
    val infoCardMidPx = remember { mutableFloatStateOf(Float.NaN) }
    val initialInfoCardTopPx = remember { mutableFloatStateOf(Float.NaN) }
    val initialInfoCardMidPx = remember { mutableFloatStateOf(Float.NaN) }
    return DetailsScreenState(
        _showDeleteLocalDialog = showDeleteLocalDialog,
        _showShareOptions = showShareOptions,
        _pendingAuthorSearch = pendingAuthorSearch,
        _pendingTagSearch = pendingTagSearch,
        _showFavoriteDialog = showFavoriteDialog,
        _showDownloadDialog = showDownloadDialog,
        _showReadingRecordSheet = showReadingRecordSheet,
        _showCommentsDialog = showCommentsDialog,
        _showReviewsDialog = showReviewsDialog,
        _selectedSupplementalRelationItem = selectedSupplementalRelationItem,
        _showMetadataSourceDialog = showMetadataSourceDialog,
        _showReadingSourceDialog = showReadingSourceDialog,
        _isModernDockCompact = isModernDockCompact,
        _toolbarBottomPx = toolbarBottomPx,
        _lastToolbarBottomPx = lastToolbarBottomPx,
        _infoCardTopPx = infoCardTopPx,
        _infoCardMidPx = infoCardMidPx,
        _initialInfoCardTopPx = initialInfoCardTopPx,
        _initialInfoCardMidPx = initialInfoCardMidPx,
    )
}