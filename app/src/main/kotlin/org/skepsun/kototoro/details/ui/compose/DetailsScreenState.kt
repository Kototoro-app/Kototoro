package org.skepsun.kototoro.details.ui.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
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
 */
internal class DetailsScreenState internal constructor(
    val showDeleteLocalDialog: MutableState<Boolean>,
    val showShareOptions: MutableState<Boolean>,
    val pendingAuthorSearch: MutableState<PendingAuthorSearch?>,
    val pendingTagSearch: MutableState<ContentTag?>,
    val showFavoriteDialog: MutableState<Boolean>,
    val showDownloadDialog: MutableState<Boolean>,
    val showReadingRecordSheet: MutableState<Boolean>,
    val showCommentsDialog: MutableState<Boolean>,
    val showReviewsDialog: MutableState<Boolean>,
    val selectedSupplementalRelationItem: MutableState<EntityRelationItem?>,
    val showMetadataSourceDialog: MutableState<Boolean>,
    val showReadingSourceDialog: MutableState<Boolean>,
    val isModernDockCompact: MutableState<Boolean>,
    val toolbarBottomPx: MutableState<Float>,
    val lastToolbarBottomPx: MutableState<Float>,
    val infoCardTopPx: MutableState<Float>,
    val infoCardMidPx: MutableState<Float>,
    val initialInfoCardTopPx: MutableState<Float>,
    val initialInfoCardMidPx: MutableState<Float>,
)

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
        showDeleteLocalDialog = showDeleteLocalDialog,
        showShareOptions = showShareOptions,
        pendingAuthorSearch = pendingAuthorSearch,
        pendingTagSearch = pendingTagSearch,
        showFavoriteDialog = showFavoriteDialog,
        showDownloadDialog = showDownloadDialog,
        showReadingRecordSheet = showReadingRecordSheet,
        showCommentsDialog = showCommentsDialog,
        showReviewsDialog = showReviewsDialog,
        selectedSupplementalRelationItem = selectedSupplementalRelationItem,
        showMetadataSourceDialog = showMetadataSourceDialog,
        showReadingSourceDialog = showReadingSourceDialog,
        isModernDockCompact = isModernDockCompact,
        toolbarBottomPx = toolbarBottomPx,
        lastToolbarBottomPx = lastToolbarBottomPx,
        infoCardTopPx = infoCardTopPx,
        infoCardMidPx = infoCardMidPx,
        initialInfoCardTopPx = initialInfoCardTopPx,
        initialInfoCardMidPx = initialInfoCardMidPx,
    )
}
