package org.skepsun.kototoro.details.ui.compose


import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Alignment
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource

import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.ui.compose.rememberSafePainter
import org.skepsun.kototoro.core.ui.glass.GlassDefaults
import org.skepsun.kototoro.core.ui.glass.rememberGlassSurfaceColors
import org.skepsun.kototoro.core.ui.theme.LocalInterfaceStyleTokens
import org.skepsun.kototoro.core.ui.theme.LocalMaterialExpressiveComponentsEnabled
import org.skepsun.kototoro.core.util.ext.takeIfUsableImageUri
import org.skepsun.kototoro.entitygraph.ui.details.EntityRelationItem
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DetailsPlainBottomSheet(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val expressive = LocalMaterialExpressiveComponentsEnabled.current
    val sheetColors = rememberGlassSurfaceColors(style = GlassDefaults.regularStyle())
    val sheetCornerRadius = LocalInterfaceStyleTokens.current.sheetCornerRadius
    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = sheetState,
        sheetGesturesEnabled = false,
        containerColor = if (expressive) {
            sheetColors.containerColor.detailsPanelContainerColor()
        } else {
            MaterialTheme.colorScheme.surface.copy(alpha = 0.96f)
        },
        contentColor = MaterialTheme.colorScheme.onSurface,
        tonalElevation = 0.dp,
        shape = RoundedCornerShape(topStart = sheetCornerRadius, topEnd = sheetCornerRadius),
        dragHandle = null,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .then(modifier),
            content = content,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DetailsTranslucentBottomSheet(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val sheetColors = rememberGlassSurfaceColors(
        style = GlassDefaults.regularStyle(),
        glassPrefs = rememberDetailsSheetGlassPrefs(),
    )
    val sheetCornerRadius = LocalInterfaceStyleTokens.current.sheetCornerRadius
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = sheetState,
        sheetGesturesEnabled = false,
        containerColor = Color.Transparent,
        contentColor = MaterialTheme.colorScheme.onSurface,
        tonalElevation = 0.dp,
        shape = RoundedCornerShape(0.dp),
        dragHandle = null,
        contentWindowInsets = { WindowInsets(0, 0, 0, 0) },
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .then(modifier),
            shape = RoundedCornerShape(topStart = sheetCornerRadius, topEnd = sheetCornerRadius),
            color = sheetColors.containerColor.detailsPanelContainerColor(),
            border = sheetColors.border,
            tonalElevation = 0.dp,
            shadowElevation = 0.dp,
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                content = content,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TrackingRelationItemSheet(
    item: EntityRelationItem,
    onDismissRequest: () -> Unit,
    onOpenExternal: (String) -> Unit,
) {
    val context = LocalContext.current
    val listState = rememberLazyListState()
    DetailsTranslucentBottomSheet(
        onDismissRequest = onDismissRequest,
        modifier = Modifier.fillMaxHeight(0.92f),
    ) {
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(),
            contentPadding = PaddingValues(start = 16.dp, top = 4.dp, end = 16.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Surface(
                    modifier = Modifier
                        .width(112.dp)
                        .aspectRatio(0.72f),
                    shape = RoundedCornerShape(20.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.36f),
                ) {
                    Box(modifier = Modifier.fillMaxSize()) {
                        if (item.coverUrl.isNullOrBlank()) {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.08f)),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    painter = rememberSafePainter(R.drawable.ic_placeholder),
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.size(32.dp),
                                )
                            }
                        } else {
                            val normalizedCoverUrl = item.coverUrl?.takeIfUsableImageUri()
                            AsyncImage(
                                model = ImageRequest.Builder(context)
                                    .data(normalizedCoverUrl)
                                    .crossfade(true)
                                    .build(),
                                contentDescription = item.name,
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Crop,
                                error = rememberSafePainter(R.drawable.ic_placeholder),
                                placeholder = rememberSafePainter(R.drawable.ic_placeholder),
                            )
                        }
                    }
                }
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = item.name,
                        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold),
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    item.subtitle?.takeIf { it.isNotBlank() }?.let { subtitle ->
                        Surface(
                            shape = RoundedCornerShape(999.dp),
                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                        ) {
                            Text(
                                text = subtitle,
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            )
                        }
                    }
                    item.supportingText?.takeIf { it.isNotBlank() }?.let { supportingText ->
                        Text(
                            text = supportingText,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }
            }

            item.subtitle?.takeIf { it.isNotBlank() }?.let { role ->
                item {
                    TrackingRelationMetaBlock(
                        label = stringResource(R.string.details_character_role_label),
                        value = role,
                    )
                }
            }

            item.detailLines
                .takeIf { it.isNotEmpty() }
                ?.joinToString(separator = "\n")
                ?.let { voiceActors ->
                    item {
                        TrackingRelationMetaBlock(
                            label = stringResource(R.string.details_character_voice_actors_label),
                            value = voiceActors,
                        )
                    }
                }

            item.url?.takeIf { it.isNotBlank() }?.let { url ->
                item {
                    Button(
                        onClick = { onOpenExternal(url) },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(18.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary,
                        ),
                    ) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                painter = rememberSafePainter(R.drawable.ic_open_external),
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                            Text(text = stringResource(R.string.details_open_character_site))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TrackingRelationMetaBlock(
    label: String,
    value: String,
) {
    val expressive = LocalMaterialExpressiveComponentsEnabled.current
    val blockColors = rememberGlassSurfaceColors(
        style = GlassDefaults.subtleStyle(),
        glassPrefs = rememberDetailsSheetGlassPrefs(),
    )
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(if (expressive) 28.dp else 22.dp),
        color = blockColors.containerColor.detailsPanelContainerColor(),
        border = blockColors.border,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = value,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TrackingReviewsSheet(
    reviews: List<org.skepsun.kototoro.tracking.discovery.domain.TrackingSiteItemDetails.ReviewEntry>,
    externalUrl: String?,
    onDismissRequest: () -> Unit,
    onOpenExternal: (String) -> Unit,
) {
    val listState = rememberLazyListState()
    val expressive = LocalMaterialExpressiveComponentsEnabled.current
    val reviewCardColors = rememberGlassSurfaceColors(
        style = GlassDefaults.subtleStyle(),
        glassPrefs = rememberDetailsSheetGlassPrefs(),
    )
    DetailsTranslucentBottomSheet(
        onDismissRequest = onDismissRequest,
        modifier = Modifier.fillMaxHeight(0.92f),
    ) {
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(),
            contentPadding = PaddingValues(start = 16.dp, top = 4.dp, end = 16.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.details_reviews),
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurface,
                )
                if (!externalUrl.isNullOrBlank()) {
                    TextButton(onClick = { onOpenExternal(externalUrl) }) {
                        Text(stringResource(R.string.details_more_reviews))
                    }
                }
            }
            }
            if (reviews.isEmpty()) {
                item {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(if (expressive) 28.dp else 24.dp),
                        color = reviewCardColors.containerColor.detailsPanelContainerColor(),
                        border = reviewCardColors.border,
                        tonalElevation = 0.dp,
                        shadowElevation = 0.dp,
                    ) {
                        Text(
                            text = stringResource(R.string.details_no_reviews),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 18.dp),
                        )
                    }
                }
            } else {
                items(reviews, key = { review -> review.url }) { review ->
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(if (expressive) 28.dp else 24.dp),
                        color = reviewCardColors.containerColor.detailsPanelContainerColor(),
                        border = reviewCardColors.border,
                        tonalElevation = 0.dp,
                        shadowElevation = 0.dp,
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 14.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                verticalAlignment = Alignment.Top,
                            ) {
                                AsyncImage(
                                    model = review.avatarUrl?.takeIfUsableImageUri(),
                                    contentDescription = review.authorName,
                                    modifier = Modifier
                                        .size(40.dp)
                                        .clip(RoundedCornerShape(14.dp)),
                                    contentScale = ContentScale.Crop,
                                    error = rememberSafePainter(R.drawable.ic_placeholder),
                                    placeholder = rememberSafePainter(R.drawable.ic_placeholder),
                                )
                                Column(
                                    modifier = Modifier.weight(1f),
                                    verticalArrangement = Arrangement.spacedBy(4.dp),
                                ) {
                                    Text(
                                        text = review.title,
                                        style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
                                        color = MaterialTheme.colorScheme.onSurface,
                                    )
                                    val metaLine = buildList {
                                        add(review.authorName)
                                        review.postedAt?.takeIf { it.isNotBlank() }?.let(::add)
                                        review.repliesCount?.let { replies ->
                                            add(stringResource(R.string.details_review_reply_count, replies))
                                        }
                                    }.joinToString(" · ")
                                    Text(
                                        text = metaLine,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurface,
                                    )
                                }
                            }
                            Text(
                                text = review.excerpt,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            TextButton(
                                modifier = Modifier.align(Alignment.End),
                                onClick = { onOpenExternal(review.url) },
                            ) {
                                Text(stringResource(R.string.details_open_review))
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TrackingCommentsSheet(
    threads: List<org.skepsun.kototoro.tracking.discovery.domain.TrackingSiteItemDetails.CommentThread>,
    externalUrl: String?,
    onDismissRequest: () -> Unit,
    onOpenExternal: (String) -> Unit,
) {
    val listState = rememberLazyListState()
    val expressive = LocalMaterialExpressiveComponentsEnabled.current
    val commentCardColors = rememberGlassSurfaceColors(
        style = GlassDefaults.subtleStyle(),
        glassPrefs = rememberDetailsSheetGlassPrefs(),
    )
    DetailsTranslucentBottomSheet(
        onDismissRequest = onDismissRequest,
        modifier = Modifier.fillMaxHeight(0.92f),
    ) {
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(),
            contentPadding = PaddingValues(start = 16.dp, top = 4.dp, end = 16.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.details_comments),
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurface,
                )
                if (!externalUrl.isNullOrBlank()) {
                    TextButton(onClick = { onOpenExternal(externalUrl) }) {
                        Text(stringResource(R.string.details_more_comments))
                    }
                }
            }
            }
            if (threads.isEmpty()) {
                item {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(if (expressive) 28.dp else 24.dp),
                        color = commentCardColors.containerColor.detailsPanelContainerColor(),
                        border = commentCardColors.border,
                        tonalElevation = 0.dp,
                        shadowElevation = 0.dp,
                    ) {
                        Text(
                            text = stringResource(R.string.details_no_comments),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 18.dp),
                        )
                    }
                }
            } else {
                items(threads, key = { thread -> "${thread.userName}:${thread.postedAt}:${thread.content.hashCode()}" }) { thread ->
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(if (expressive) 28.dp else 24.dp),
                        color = commentCardColors.containerColor.detailsPanelContainerColor(),
                        border = commentCardColors.border,
                        tonalElevation = 0.dp,
                        shadowElevation = 0.dp,
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 14.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                verticalAlignment = Alignment.Top,
                            ) {
                                AsyncImage(
                                    model = thread.avatarUrl?.takeIfUsableImageUri(),
                                    contentDescription = thread.userName,
                                    modifier = Modifier
                                        .size(40.dp)
                                        .clip(RoundedCornerShape(14.dp)),
                                    contentScale = ContentScale.Crop,
                                    error = rememberSafePainter(R.drawable.ic_placeholder),
                                    placeholder = rememberSafePainter(R.drawable.ic_placeholder),
                                )
                                Column(
                                    modifier = Modifier.weight(1f),
                                    verticalArrangement = Arrangement.spacedBy(4.dp),
                                ) {
                                    Text(
                                        text = thread.userName,
                                        style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
                                        color = MaterialTheme.colorScheme.onSurface,
                                    )
                                    val metaLine = buildList {
                                        thread.rating?.let { add(String.format(Locale.ROOT, "%.1f", it)) }
                                        thread.status?.takeIf { it.isNotBlank() }?.let(::add)
                                        thread.postedAt?.takeIf { it.isNotBlank() }?.let(::add)
                                    }.joinToString(" · ")
                                    if (metaLine.isNotBlank()) {
                                        Text(
                                            text = metaLine,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurface,
                                        )
                                    }
                                }
                            }
                            Text(
                                text = thread.content,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            if (thread.replies.isNotEmpty()) {
                                val replyCardColors = rememberGlassSurfaceColors(style = GlassDefaults.subtleStyle())
                                Column(
                                    verticalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.28f))
                                    thread.replies.forEach { reply ->
                                        Surface(
                                            shape = RoundedCornerShape(if (expressive) 22.dp else 18.dp),
                                            color = if (expressive) {
                                                replyCardColors.containerColor.detailsPanelContainerColor()
                                            } else {
                                                MaterialTheme.colorScheme.surface.copy(alpha = 0.26f)
                                            },
                                            border = if (expressive) replyCardColors.border else null,
                                        ) {
                                            Column(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                                                verticalArrangement = Arrangement.spacedBy(4.dp),
                                            ) {
                                                Text(
                                                    text = buildString {
                                                        append(reply.userName)
                                                        reply.postedAt?.takeIf { it.isNotBlank() }?.let {
                                                            append(" · ")
                                                            append(it)
                                                        }
                                                    },
                                                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                                                    color = MaterialTheme.colorScheme.onSurface,
                                                )
                                                Text(
                                                    text = reply.content,
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onSurface,
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

