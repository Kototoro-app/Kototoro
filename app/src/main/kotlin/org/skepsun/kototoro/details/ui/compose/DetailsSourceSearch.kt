package org.skepsun.kototoro.details.ui.compose


import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.shape.RoundedCornerShape
import com.kyant.shapes.RoundedRectangle
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.model.titleResId
import org.skepsun.kototoro.core.ui.compose.SheetDragHandle
import org.skepsun.kototoro.core.ui.compose.rememberSafePainter
import org.skepsun.kototoro.core.ui.compose.rememberResolvedSourceTitle
import org.skepsun.kototoro.core.ui.glass.GlassDefaults
import org.skepsun.kototoro.core.ui.glass.GlassSurface
import org.skepsun.kototoro.core.ui.glass.rememberGlassPrefsOrFallback
import org.skepsun.kototoro.core.ui.glass.rememberGlassSurfaceColors
import org.skepsun.kototoro.core.ui.theme.LocalMaterialExpressiveComponentsEnabled
import org.skepsun.kototoro.core.util.ext.mangaSourceExtra
import org.skepsun.kototoro.core.util.ext.takeIfUsableImageUri
import org.skepsun.kototoro.details.ui.model.EntityChapterSourceInfo
import org.skepsun.kototoro.parsers.model.Content
import org.skepsun.kototoro.tracking.discovery.domain.TrackingSiteItem
import kotlin.math.roundToInt

@Composable
internal fun SourceSearchField(
    value: String,
    onValueChange: (String) -> Unit,
    onSearch: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var isFocused by remember { mutableStateOf(false) }
    val expressive = LocalMaterialExpressiveComponentsEnabled.current
    val shape = RoundedCornerShape(if (expressive) 999.dp else 22.dp)
    val searchFieldColors = rememberGlassSurfaceColors(style = GlassDefaults.subtleStyle())
    val containerColor = if (expressive) {
        if (isFocused) {
            searchFieldColors.containerColor.detailsButtonContainerColor()
        } else {
            searchFieldColors.containerColor.detailsButtonContainerColor()
        }
    } else if (isFocused) {
        searchFieldColors.containerColor
    } else {
        searchFieldColors.containerColor.copy(
            alpha = (searchFieldColors.containerColor.alpha * 0.92f).coerceAtLeast(0.12f),
        )
    }
    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 0.dp) {
        Surface(
            modifier = modifier.height(44.dp),
            shape = shape,
            color = containerColor,
            border = if (expressive) {
                BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.34f))
            } else {
                searchFieldColors.border
            },
            tonalElevation = 0.dp,
            shadowElevation = 0.dp,
        ) {
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier
                    .fillMaxSize()
                    .onFocusChanged { isFocused = it.isFocused },
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium.copy(
                    color = MaterialTheme.colorScheme.onSurface,
                    lineHeight = MaterialTheme.typography.bodyMedium.fontSize,
                ),
                keyboardOptions = KeyboardOptions(
                    imeAction = androidx.compose.ui.text.input.ImeAction.Search,
                ),
                keyboardActions = KeyboardActions(
                    onSearch = { onSearch() },
                ),
                decorationBox = { innerTextField ->
                    Row(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(start = 12.dp, end = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Search,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.size(18.dp),
                        )
                        Box(
                            modifier = Modifier.weight(1f),
                            contentAlignment = Alignment.CenterStart,
                        ) {
                            if (value.isEmpty()) {
                                Text(
                                    text = stringResource(R.string.search),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                            }
                            innerTextField()
                        }
                    }
                },
            )
        }
    }
}

@Composable
internal fun DetailsSourceOverlayDialog(
    onDismissRequest: () -> Unit,
    content: @Composable (panelDragModifier: Modifier) -> Unit,
) {
    var panelOffsetY by remember { mutableFloatStateOf(0f) }
    val expressive = LocalMaterialExpressiveComponentsEnabled.current
    val panelColors = rememberGlassSurfaceColors(
        style = GlassDefaults.regularStyle(),
        glassPrefs = rememberDetailsSourceOverlayGlassPrefs(),
    )
    val density = androidx.compose.ui.platform.LocalDensity.current
    val dismissThresholdPx = remember(density) {
        with(density) { 96.dp.toPx() }
    }
    val panelDragModifier = Modifier.pointerInput(dismissThresholdPx, onDismissRequest) {
        detectVerticalDragGestures(
            onVerticalDrag = { change, dragAmount ->
                change.consume()
                panelOffsetY = (panelOffsetY + dragAmount).coerceAtLeast(0f)
            },
            onDragCancel = {
                panelOffsetY = 0f
            },
            onDragEnd = {
                if (panelOffsetY > dismissThresholdPx) {
                    onDismissRequest()
                } else {
                    panelOffsetY = 0f
                }
            },
        )
    }
    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.18f))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onDismissRequest,
                ),
            contentAlignment = Alignment.BottomCenter,
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(0.92f)
                    .offset { IntOffset(0, panelOffsetY.roundToInt()) }
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = {},
                ),
                shape = RoundedCornerShape(topStart = if (expressive) 36.dp else 28.dp, topEnd = if (expressive) 36.dp else 28.dp),
                color = panelColors.containerColor.detailsPanelContainerColor(),
                border = panelColors.border,
                tonalElevation = 0.dp,
                shadowElevation = 0.dp,
            ) {
                Column(modifier = Modifier.fillMaxSize()) {
                    SheetDragHandle(
                        modifier = Modifier
                            .then(panelDragModifier)
                            .align(Alignment.CenterHorizontally),
                    )
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                    ) {
                        Box(modifier = Modifier.fillMaxSize()) {
                            content(panelDragModifier)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun rememberDetailsSourceOverlayGlassPrefs() =
    rememberGlassPrefsOrFallback()

@Composable
internal fun MetadataSearchSection(
    section: org.skepsun.kototoro.details.ui.MetadataSearchSectionUiState,
    isAuthorized: Boolean,
    hasSearched: Boolean,
    onItemClick: (TrackingSiteItem) -> Unit,
    onBindClick: (TrackingSiteItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(section.service.titleResId),
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = if (isAuthorized) section.items.size.toString() else stringResource(R.string.sign_in),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        section.errorMessage?.let { errorMessage ->
            Text(
                text = errorMessage,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        when {
            section.items.isNotEmpty() -> {
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(horizontal = 2.dp),
            ) {
                itemsIndexed(
                    items = section.items,
                    key = { index, item -> "${item.service.id}:${item.remoteId}:$index" },
                ) { _, item ->
                    TrackingSearchResultCard(
                        item = item,
                        onClick = { onItemClick(item) },
                        onBindClick = { onBindClick(item) },
                    )
                }
            }
            }
            section.isLoading -> {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    Text(
                        text = stringResource(R.string.search),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
            hasSearched && section.errorMessage == null -> {
                Text(
                    text = stringResource(R.string.nothing_found),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

@Composable
internal fun ReadingSearchSection(
    section: org.skepsun.kototoro.details.ui.ReadingSearchSectionUiState,
    hasSearched: Boolean,
    onItemClick: (Content) -> Unit,
    onMigrateClick: (Content) -> Unit,
    modifier: Modifier = Modifier,
) {
    val expressive = LocalMaterialExpressiveComponentsEnabled.current
    val sectionColors = rememberGlassSurfaceColors(style = GlassDefaults.subtleStyle())
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(if (expressive) 28.dp else 20.dp),
        color = sectionColors.containerColor.detailsPanelContainerColor(),
        border = sectionColors.border,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = rememberResolvedSourceTitle(section.source.mangaSource),
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurface,
            )
            section.errorMessage?.let { errorMessage ->
                Text(
                    text = errorMessage,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            when {
                section.items.isNotEmpty() -> {
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        contentPadding = PaddingValues(horizontal = 2.dp),
                    ) {
                        itemsIndexed(
                            items = section.items,
                            key = { index, item -> "${item.id}:${item.source.name}:$index" },
                        ) { _, item ->
                            ReadingSearchResultCard(
                                item = item,
                                onClick = { onItemClick(item) },
                                onMigrateClick = { onMigrateClick(item) },
                            )
                        }
                    }
                }
                section.isLoading -> {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        Text(
                            text = stringResource(R.string.search),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
                hasSearched && section.errorMessage == null -> {
                    Text(
                        text = stringResource(R.string.nothing_found),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }
    }
}

@Composable
private fun SourceOptionSheetRow(
    title: String,
    subtitle: String,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    val content: @Composable () -> Unit = {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(horizontal = 16.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            if (isSelected) {
                Icon(
                    imageVector = Icons.Filled.Check,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
    DetailsSearchRowSurface {
        content()
    }
}

@Composable
private fun TrackingSearchResultRow(
    item: TrackingSiteItem,
    onClick: () -> Unit,
) {
    val content: @Composable () -> Unit = {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(horizontal = 14.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TrackingCoverImage(
                coverUrl = item.coverUrl,
                contentDescription = item.title,
                modifier = Modifier
                    .width(42.dp)
                    .height(58.dp)
                    .clip(RoundedCornerShape(12.dp)),
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = item.title,
                    style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                item.altTitle?.takeIf { it.isNotBlank() }?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(
                    text = stringResource(item.service.titleResId),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
    DetailsSearchRowSurface {
        content()
    }
}

@Composable
private fun DetailsSearchRowSurface(
    content: @Composable () -> Unit,
) {
    val expressive = LocalMaterialExpressiveComponentsEnabled.current
    if (expressive) {
        GlassSurface(
            modifier = Modifier.fillMaxWidth(),
            style = GlassDefaults.subtleStyle(),
            shape = RoundedRectangle(24.dp),
        ) {
            content()
        }
    } else {
        GlassSurface(
            modifier = Modifier.fillMaxWidth(),
            style = GlassDefaults.subtleStyle(),
            shape = RoundedRectangle(20.dp),
        ) {
            content()
        }
    }
}

@Composable
private fun TrackingSearchResultCard(
    item: TrackingSiteItem,
    onClick: () -> Unit,
    onBindClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val expressive = LocalMaterialExpressiveComponentsEnabled.current
    val resultCardColors = rememberGlassSurfaceColors(style = GlassDefaults.subtleStyle())
    Surface(
        modifier = modifier.width(108.dp),
        shape = RoundedCornerShape(if (expressive) 24.dp else 18.dp),
        color = resultCardColors.containerColor.detailsPanelContainerColor(),
        border = resultCardColors.border,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(
                    onClick = onClick,
                    onLongClick = onBindClick,
                )
                .padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            TrackingCoverImage(
                coverUrl = item.coverUrl,
                contentDescription = item.title,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(142.dp)
                    .clip(RoundedCornerShape(14.dp)),
            )
            Text(
                text = item.title,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            item.altTitle?.takeIf { it.isNotBlank() }?.let { altTitle ->
                Text(
                    text = altTitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            val infoParts = buildList {
                item.score?.let { score ->
                    val max = item.scoreMax ?: 10f
                    add("%.1f".format(score / max * 10))
                }
                item.totalEpisodes?.let { count ->
                    add("$count EP")
                }
            }
            if (infoParts.isNotEmpty()) {
                Text(
                    text = infoParts.joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            FilledTonalButton(
                onClick = onBindClick,
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_replace),
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = stringResource(R.string.migrate),
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
    }
}

@Composable
private fun TrackingCoverImage(
    coverUrl: String?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
) {
    SourceCoverImage(
        model = coverUrl?.takeIfUsableImageUri(),
        contentDescription = contentDescription,
        modifier = modifier,
    )
}

@Composable
private fun SourceCoverImage(
    model: Any?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier.background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        if (model == null) {
            Icon(
                painter = rememberSafePainter(R.drawable.ic_placeholder),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(24.dp),
            )
        } else {
            AsyncImage(
                model = model,
                contentDescription = contentDescription,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        }
    }
}

@Composable
private fun ReadingSearchResultRow(
    item: Content,
    onClick: () -> Unit,
) {
    val latestChapterInfo = remember(item) { item.readingSearchLatestChapterInfo() }
    val context = LocalContext.current
    val expressive = LocalMaterialExpressiveComponentsEnabled.current
    val resultCardColors = rememberGlassSurfaceColors(style = GlassDefaults.subtleStyle())
    val coverUrl = item.coverUrl?.takeIfUsableImageUri()
    val coverRequest = remember(item.id, coverUrl, item.source) {
        coverUrl?.let {
            ImageRequest.Builder(context)
                .data(it)
                .mangaSourceExtra(item.source)
                .build()
        }
    }
    GlassSurface(
        modifier = Modifier.fillMaxWidth(),
        style = GlassDefaults.subtleStyle(),
        shape = RoundedRectangle(20.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(horizontal = 14.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SourceCoverImage(
                model = coverRequest,
                contentDescription = item.title,
                modifier = Modifier
                    .width(42.dp)
                    .height(58.dp)
                    .clip(RoundedCornerShape(12.dp)),
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = item.title,
                    style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = rememberResolvedSourceTitle(item.source),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                latestChapterInfo?.let { latestInfo ->
                    Text(
                        text = when (latestInfo) {
                            is ReadingSearchLatestChapterInfo.Numbered -> {
                                stringResource(
                                    R.string.details_search_result_latest_chapter,
                                    latestInfo.number,
                                )
                            }
                            is ReadingSearchLatestChapterInfo.Titled -> {
                                stringResource(
                                    R.string.details_search_result_latest_title,
                                    latestInfo.title,
                                )
                            }
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
@OptIn(ExperimentalFoundationApi::class)
private fun ReadingSearchResultCard(
    item: Content,
    onClick: () -> Unit,
    onMigrateClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val latestChapterInfo = remember(item) { item.readingSearchLatestChapterInfo() }
    val context = LocalContext.current
    val expressive = LocalMaterialExpressiveComponentsEnabled.current
    val resultCardColors = rememberGlassSurfaceColors(style = GlassDefaults.subtleStyle())
    val coverUrl = item.coverUrl?.takeIfUsableImageUri()
    val coverRequest = remember(item.id, coverUrl, item.source) {
        coverUrl?.let {
            ImageRequest.Builder(context)
                .data(it)
                .mangaSourceExtra(item.source)
                .build()
        }
    }
    Surface(
        modifier = modifier.width(108.dp),
        shape = RoundedCornerShape(if (expressive) 24.dp else 18.dp),
        color = resultCardColors.containerColor.detailsPanelContainerColor(),
        border = resultCardColors.border,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(
                    onClick = onClick,
                    onLongClick = onMigrateClick,
                )
                .padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SourceCoverImage(
                model = coverRequest,
                contentDescription = item.title,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(142.dp)
                    .clip(RoundedCornerShape(14.dp)),
            )
            Text(
                text = item.title,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            val chaptersCount = item.chapters?.size ?: 0
            if (chaptersCount > 0) {
                Text(
                    text = pluralStringResource(R.plurals.chapters, chaptersCount, chaptersCount),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            latestChapterInfo?.let { latestInfo ->
                Text(
                    text = when (latestInfo) {
                        is ReadingSearchLatestChapterInfo.Numbered -> {
                            stringResource(
                                R.string.details_search_result_latest_chapter,
                                latestInfo.number,
                            )
                        }
                        is ReadingSearchLatestChapterInfo.Titled -> {
                            stringResource(
                                R.string.details_search_result_latest_title,
                                latestInfo.title,
                            )
                        }
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            FilledTonalButton(
                onClick = onMigrateClick,
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_replace),
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = stringResource(R.string.migrate),
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
    }
}

private sealed interface ReadingSearchLatestChapterInfo {
    data class Numbered(val number: String) : ReadingSearchLatestChapterInfo
    data class Titled(val title: String) : ReadingSearchLatestChapterInfo
}

private fun Content.readingSearchLatestChapterInfo(): ReadingSearchLatestChapterInfo? {
    val chapters = chapters.orEmpty()
    if (chapters.isEmpty()) return null

    val numberedChapter = chapters
        .asSequence()
        .filter { it.number > 0f }
        .maxByOrNull { it.number }
    if (numberedChapter != null) {
        return ReadingSearchLatestChapterInfo.Numbered(
            numberedChapter.numberString().orEmpty(),
        )
    }

    val titledChapter = chapters.firstNotNullOfOrNull { chapter ->
        chapter.title?.takeIf { it.isNotBlank() }
    } ?: return null
    return ReadingSearchLatestChapterInfo.Titled(titledChapter)
}

@Composable
private fun EntityChapterSourceCard(
    info: EntityChapterSourceInfo,
) {
    val chapterSourceTitle = info.projectionTitle?.takeIf { it.isNotBlank() }
        ?: stringResource(R.string.entity_graph_chapter_source_unavailable)
    val supportingText = if (info.source != null) {
        buildString {
            append(stringResource(R.string.entity_graph_chapter_source_selected_hint))
            if (info.projectionCount > 1) {
                append(' ')
                append(stringResource(R.string.entity_graph_chapter_source_projection_count, info.projectionCount))
            }
        }
    } else {
        stringResource(R.string.entity_graph_chapter_source_unavailable_hint)
    }
    GlassSurface(
        modifier = Modifier.fillMaxWidth(),
        style = GlassDefaults.subtleStyle(),
        shape = RoundedRectangle(24.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_book_page),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp),
                )
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = stringResource(R.string.entity_graph_chapter_source),
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = chapterSourceTitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = supportingText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

private fun Modifier.offsetX(
    maxOffset: Dp,
    progress: Float,
): Modifier = this.then(
    Modifier.offset(x = maxOffset * progress),
)

