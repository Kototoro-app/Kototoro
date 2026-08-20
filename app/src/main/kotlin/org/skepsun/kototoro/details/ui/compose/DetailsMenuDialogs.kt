package org.skepsun.kototoro.details.ui.compose


import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.res.stringResource

import androidx.compose.ui.unit.dp
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.model.isNsfw
import org.skepsun.kototoro.core.prefs.AppSettings
import org.skepsun.kototoro.core.prefs.InterfaceStyle
import org.skepsun.kototoro.core.ui.compose.rememberSafePainter
import org.skepsun.kototoro.core.ui.glass.GlassDefaults
import org.skepsun.kototoro.core.ui.theme.LocalInterfaceStyle
import org.skepsun.kototoro.core.ui.theme.LocalInterfaceStyleTokens
import org.skepsun.kototoro.details.ui.model.HistoryInfo
import org.skepsun.kototoro.parsers.model.ContentSource
import org.skepsun.kototoro.parsers.model.ContentType
import org.skepsun.kototoro.main.ui.compose.GlassDropdownMenu
import org.skepsun.kototoro.main.ui.compose.CompactDropdownMenuItem

internal fun calculateDetailsScrollProgress(
    scrollValue: Int,
    landscapeScrollValue: Int,
    toolbarBottomPx: Float,
    infoCardTopPx: Float,
    initialInfoCardTopPx: Float,
    toolbarGapPx: Float,
    isWideAdaptiveLayout: Boolean,
    disableInWideLayout: Boolean,
): Float {
    if (disableInWideLayout && isWideAdaptiveLayout) {
        return 0f
    }
    val targetTop = toolbarBottomPx + toolbarGapPx
    return if (toolbarBottomPx.isFinite() && infoCardTopPx.isFinite() && initialInfoCardTopPx.isFinite()) {
        val travelDistance = (initialInfoCardTopPx - targetTop).coerceAtLeast(1f)
        ((initialInfoCardTopPx - infoCardTopPx) / travelDistance).coerceIn(0f, 1f)
    } else {
        val fallbackScroll = if (isWideAdaptiveLayout) landscapeScrollValue else scrollValue
        (fallbackScroll / 360f).coerceIn(0f, 1f)
    }
}

internal fun easedOpacityProgress(progress: Float): Float {
    val clamped = progress.coerceIn(0f, 1f)
    return clamped * clamped * (3f - 2f * clamped)
}

@Composable
internal fun resolveReadActionLabel(
    contentType: ContentType?,
    historyInfo: HistoryInfo,
    isLoading: Boolean,
): String {
    val isChaptersLoading = isLoading && (historyInfo.totalChapters <= 0 || historyInfo.isChapterMissing)
    val defaultReadRes = when (contentType) {
        ContentType.VIDEO, ContentType.HENTAI_VIDEO -> R.string.play
        else -> R.string.read
    }
    val continueRes = when (contentType) {
        ContentType.VIDEO, ContentType.HENTAI_VIDEO -> R.string._continue_play
        else -> R.string._continue
    }
    return stringResource(
        when {
            isChaptersLoading -> R.string.loading_
            historyInfo.isIncognitoMode -> R.string.incognito
            historyInfo.canContinue -> continueRes
            else -> defaultReadRes
        },
    )
}

@Composable
internal fun DetailsOverflowMenu(
    contentTitle: String?,
    showTranslateAction: Boolean,
    hasTranslationCache: Boolean,
    isShowingTranslation: Boolean,
    isTranslating: Boolean,
    hasMetadataBrowserTarget: Boolean,
    hasLocalBrowserTarget: Boolean,
    localBrowserTitleRes: Int,
    hasOnlineVariant: Boolean,
    isReadingRecordAvailable: Boolean,
    isDeleteLocalAvailable: Boolean,
    isEditOverrideAvailable: Boolean,
    isShortcutSupported: Boolean,
    isNsfw: Boolean,
    onDeleteLocalRequest: () -> Unit,
    onActionClick: (DetailsAction) -> Unit,
) {
    val interfaceStyleTokens = LocalInterfaceStyleTokens.current
    var expanded by remember { mutableStateOf(false) }
    var menuAnchorBounds by remember { mutableStateOf<Rect?>(null) }

    Box(
        modifier = Modifier.onGloballyPositioned { menuAnchorBounds = it.boundsInRoot() },
    ) {
        DetailsChromeButton(
            onClick = { expanded = true },
            modifier = Modifier.size(interfaceStyleTokens.topBarButtonSize),
        ) {
            Icon(
                imageVector = Icons.Default.MoreVert,
                contentDescription = stringResource(R.string.more),
                modifier = Modifier.size(interfaceStyleTokens.topBarIconSize),
            )
        }
        GlassDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            style = GlassDefaults.subtleStyle(),
            useRootOverlay = LocalInterfaceStyle.current == InterfaceStyle.IOS,
            anchorBounds = menuAnchorBounds,
        ) {
            if (showTranslateAction) {
                CompactDropdownMenuItem(
                    text = {
                        Text(
                            stringResource(
                                if (hasTranslationCache && isShowingTranslation) {
                                    R.string.details_show_original
                                } else if (hasTranslationCache) {
                                    R.string.details_show_translation
                                } else {
                                    R.string.details_translate_title_and_description_hint
                                },
                            ),
                        )
                    },
                    leadingIcon = {
                        DetailsMenuIcon(R.drawable.ic_translate)
                    },
                    enabled = !isTranslating,
                    onClick = {
                        expanded = false
                        onActionClick(
                            if (hasTranslationCache) {
                                DetailsAction.ToggleTranslation
                            } else {
                                DetailsAction.Translate
                            },
                        )
                    },
                )
            }
            if (isReadingRecordAvailable) {
                CompactDropdownMenuItem(
                    text = { Text(stringResource(R.string.reading_record)) },
                    leadingIcon = {
                        DetailsMenuIcon(R.drawable.ic_history)
                    },
                    onClick = {
                        expanded = false
                        onActionClick(DetailsAction.OpenReadingRecord)
                    },
                )
            }
            CompactDropdownMenuItem(
                text = { Text(stringResource(if (isNsfw) R.string.mark_as_safe else R.string.mark_as_nsfw)) },
                leadingIcon = {
                    DetailsMenuIcon(R.drawable.ic_nsfw)
                },
                onClick = {
                    expanded = false
                    onActionClick(DetailsAction.ToggleSafe)
                },
            )
            if (isDeleteLocalAvailable) {
                CompactDropdownMenuItem(
                    text = { Text(stringResource(R.string.delete)) },
                    leadingIcon = {
                        DetailsMenuIcon(R.drawable.ic_delete)
                    },
                    onClick = {
                        expanded = false
                        if (contentTitle != null) {
                            onDeleteLocalRequest()
                        }
                    },
                )
            }
            if (isEditOverrideAvailable) {
                CompactDropdownMenuItem(
                    text = { Text(stringResource(R.string.edit)) },
                    leadingIcon = {
                        DetailsMenuIcon(R.drawable.ic_edit)
                    },
                    onClick = {
                        expanded = false
                        onActionClick(DetailsAction.EditOverride)
                    },
                )
            }
            if (isShortcutSupported) {
                CompactDropdownMenuItem(
                    text = { Text(stringResource(R.string.create_shortcut)) },
                    leadingIcon = {
                        DetailsMenuIcon(R.drawable.ic_shortcut)
                    },
                    onClick = {
                        expanded = false
                        onActionClick(DetailsAction.CreateShortcut)
                    },
                )
            }
            CompactDropdownMenuItem(
                text = { Text(stringResource(R.string.find_similar)) },
                leadingIcon = {
                    DetailsMenuIcon(R.drawable.ic_search)
                },
                onClick = {
                    expanded = false
                    onActionClick(DetailsAction.FindSimilar)
                },
            )
            if (hasOnlineVariant) {
                CompactDropdownMenuItem(
                    text = { Text(stringResource(R.string.online_variant)) },
                    leadingIcon = {
                        DetailsMenuIcon(R.drawable.ic_cloud_download)
                    },
                    onClick = {
                        expanded = false
                        onActionClick(DetailsAction.OpenOnlineVariant)
                    },
                )
            }
            if (hasMetadataBrowserTarget) {
                CompactDropdownMenuItem(
                    text = { Text(stringResource(R.string.open_metadata_in_browser)) },
                    leadingIcon = {
                        DetailsMenuIcon(R.drawable.ic_open_external)
                    },
                    onClick = {
                        expanded = false
                        onActionClick(DetailsAction.OpenMetadataInBrowser)
                    },
                )
            }
            if (hasLocalBrowserTarget) {
                CompactDropdownMenuItem(
                    text = { Text(stringResource(localBrowserTitleRes)) },
                    leadingIcon = {
                        DetailsMenuIcon(R.drawable.ic_open_external)
                    },
                    onClick = {
                        expanded = false
                        onActionClick(DetailsAction.OpenLocalSourceInBrowser)
                    },
                )
            }
        }
    }
}

@Composable
internal fun DeleteLocalDialog(
    title: String,
    onDismissRequest: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = { Text(stringResource(R.string.delete_manga)) },
        text = { Text(stringResource(R.string.text_delete_local_manga, title)) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(stringResource(R.string.delete))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismissRequest) {
                Text(stringResource(android.R.string.cancel))
            }
        },
    )
}

@Composable
internal fun SearchTargetDialog(
    iconRes: Int,
    title: String,
    sourceTitle: String,
    onDismissRequest: () -> Unit,
    onSearchOnSource: () -> Unit,
    onSearchEverywhere: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismissRequest,
        icon = {
            Icon(
                painter = rememberSafePainter(iconRes),
                contentDescription = null,
            )
        },
        title = { Text(text = title) },
        text = {
            Column {
                TextButton(
                    onClick = onSearchOnSource,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = stringResource(R.string.search_on_s, sourceTitle),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                TextButton(
                    onClick = onSearchEverywhere,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = stringResource(R.string.search_everywhere),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismissRequest) {
                Text(text = stringResource(R.string.close))
            }
        },
        confirmButton = {},
    )
}

@Composable
internal fun ShareOptionsDialog(
    title: String,
    sourceTitle: String,
    onDismissRequest: () -> Unit,
    onShareAppLink: () -> Unit,
    onShareSourceLink: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismissRequest,
        icon = {
            Icon(
                imageVector = Icons.Default.Share,
                contentDescription = null,
            )
        },
        title = { Text(text = stringResource(R.string.share)) },
        text = {
            Column {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 12.dp),
                )
                TextButton(
                    onClick = onShareAppLink,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = stringResource(R.string.link_to_manga_in_app),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                TextButton(
                    onClick = onShareSourceLink,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = stringResource(R.string.link_to_manga_on_s, sourceTitle),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismissRequest) {
                Text(text = stringResource(android.R.string.cancel))
            }
        },
        confirmButton = {},
    )
}


internal data class PendingAuthorSearch(
    val author: String,
    val source: ContentSource,
)

internal fun resolveAvailableDetailsTabIds(
    contentType: ContentType?,
    settings: AppSettings,
): List<Int> = buildList {
    add(DETAILS_TAB_CHAPTERS)
    val isNovel = contentType == ContentType.NOVEL || contentType == ContentType.HENTAI_NOVEL
    val isVideo = contentType == ContentType.VIDEO || contentType == ContentType.HENTAI_VIDEO
    if (settings.isPagesTabEnabled && !isNovel && !isVideo) {
        add(DETAILS_TAB_PAGES)
    }
    if (!isVideo) {
        add(DETAILS_TAB_BOOKMARKS)
    }
}

internal fun resolveDetailsTabSelection(
    requestedTabId: Int,
    availableTabs: List<Int>,
): Int {
    return if (requestedTabId in availableTabs) {
        requestedTabId
    } else {
        when {
            requestedTabId > DETAILS_TAB_CHAPTERS -> {
                availableTabs.getOrElse((requestedTabId - 1).coerceAtLeast(0)) { availableTabs.first() }
            }
            else -> availableTabs.first()
        }
    }
}

