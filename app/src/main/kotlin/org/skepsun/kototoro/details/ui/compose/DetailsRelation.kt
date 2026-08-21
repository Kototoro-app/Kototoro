package org.skepsun.kototoro.details.ui.compose


import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import com.kyant.shapes.RoundedRectangle
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Alignment
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource

import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.ui.compose.AppLayoutTokens
import org.skepsun.kototoro.core.ui.compose.compactPosterRailCardStyle
import org.skepsun.kototoro.core.ui.compose.rememberSafePainter
import org.skepsun.kototoro.core.ui.glass.GlassDefaults
import org.skepsun.kototoro.core.ui.glass.GlassSurface
import org.skepsun.kototoro.core.util.ext.takeIfUsableImageUri
import org.skepsun.kototoro.entitygraph.ui.details.EntityRelationSection
import org.skepsun.kototoro.entitygraph.ui.details.EntityRelationItem
import org.skepsun.kototoro.list.ui.compose.KototoroContentCard
import androidx.compose.ui.tooling.preview.Preview
import org.skepsun.kototoro.core.ui.theme.KototoroTheme
import org.skepsun.kototoro.list.ui.model.ContentListModel

@Composable
fun DetailsChromeButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    IconButton(
        onClick = onClick,
        modifier = modifier,
    ) {
        content()
    }
}


@Composable
fun DetailsRelationSections(
    sections: List<EntityRelationSection>,
    modifier: Modifier = Modifier,
    outerHorizontalPadding: Dp = AppLayoutTokens.sectionHorizontalPadding,
    onItemClick: (EntityRelationItem) -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 12.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        sections.forEach { section ->
            DetailsRelationSectionContainer(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = outerHorizontalPadding),
            ) {
                EntityRelationSectionHeader(section = section)
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    contentPadding = PaddingValues(horizontal = outerHorizontalPadding),
                ) {
                    items(
                        items = section.items,
                        key = { it.stableKey },
                    ) { item ->
                        EntityRelationCard(item = item, onClick = { onItemClick(item) })
                    }
                }
            }
        }
    }
}

@Composable
internal fun DetailsRelatedContentSection(
    items: List<ContentListModel>,
    outerHorizontalPadding: Dp,
    onItemClick: (ContentListModel) -> Unit,
) {
    val cardStyle = compactPosterRailCardStyle(gridScale = 1f)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            text = stringResource(R.string.details_related_works),
            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(horizontal = outerHorizontalPadding),
        )
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(horizontal = outerHorizontalPadding),
        ) {
            items(
                count = items.size,
                key = { index -> "${items[index].source.name}:${items[index].id}:${items[index].manga.url}:$index" },
            ) { index ->
                val item = items[index]
                KototoroContentCard(
                    model = item,
                    sharedTransitionEnabled = false,
                    cardStyle = cardStyle,
                    onClick = { onItemClick(item) },
                    onLongClick = {},
                    modifier = Modifier.width(cardStyle.itemWidth + 8.dp),
                )
            }
        }
    }
}

@Composable
internal fun DetailsSupplementMetadataCard(
    properties: List<Pair<String, String>>,
    outerHorizontalPadding: Dp,
) {
    GlassSurface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = outerHorizontalPadding),
        style = GlassDefaults.subtleStyle(),
        shape = RoundedRectangle(24.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = stringResource(R.string.details_additional_metadata),
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurface,
            )
            properties.forEach { (label, value) ->
                if (value.isBlank()) {
                    return@forEach
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.width(96.dp),
                    )
                    Text(
                        text = value,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

@Composable
private fun EntityRelationSectionHeader(
    section: EntityRelationSection,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = AppLayoutTokens.sectionHorizontalPadding),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        section.titleRes?.let { titleRes ->
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f),
            ) {
                Icon(
                    painter = rememberSafePainter(entityRelationSectionIconRes(titleRes)),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .padding(10.dp)
                        .size(18.dp),
                )
            }
        }
        Text(
            text = section.titleRes?.let { stringResource(it) } ?: section.title.orEmpty(),
            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        Surface(
            shape = RoundedCornerShape(999.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.42f),
        ) {
            Text(
                text = section.items.size.toString(),
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            )
        }
    }
}

@Composable
fun EntityRelationCard(
    item: EntityRelationItem,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val type = item.type
    val typeLabel = type?.let { stringResource(entityRelationTypeLabelRes(it)) }
    val typeIconRes = type?.let { entityRelationTypeIconRes(it) }
    val opensExternalPage = type == null && item.trackingService == null && item.remoteId == null && !item.url.isNullOrBlank()
    DetailsRelationItemCard(
        modifier = modifier,
        width = if (type != null) 148.dp else 132.dp,
        title = item.name,
        subtitle = item.subtitle,
        supportingText = item.supportingText,
        onClick = onClick,
        footer = if (typeLabel != null && typeIconRes != null) {
            {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        painter = rememberSafePainter(typeIconRes),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(14.dp),
                    )
                    Text(
                        text = typeLabel,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Icon(
                        painter = rememberSafePainter(R.drawable.ic_arrow_forward),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(14.dp),
                    )
                }
            }
        } else if (opensExternalPage) {
            {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        painter = rememberSafePainter(R.drawable.ic_open_external),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(14.dp),
                    )
                    Text(
                        text = stringResource(R.string.open_website),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Icon(
                        painter = rememberSafePainter(R.drawable.ic_arrow_forward),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(14.dp),
                    )
                }
            }
        } else {
            null
        },
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(if (type != null) 0.76f else 0.72f),
            shape = RoundedCornerShape(18.dp),
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
                            painter = rememberSafePainter(typeIconRes ?: R.drawable.ic_placeholder),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.size(30.dp),
                        )
                    }
                } else {
                    val normalizedCoverUrl = item.coverUrl?.takeIfUsableImageUri()
                    AsyncImage(
                        model = normalizedCoverUrl,
                        contentDescription = item.name,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop,
                    )
                }
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            brush = Brush.verticalGradient(
                                colors = listOf(
                                    Color.Transparent,
                                    MaterialTheme.colorScheme.scrim.copy(alpha = 0.22f),
                                ),
                            ),
                        ),
                )
                if (typeLabel != null && typeIconRes != null) {
                    Surface(
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(8.dp),
                        shape = RoundedCornerShape(999.dp),
                        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.72f),
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                painter = rememberSafePainter(typeIconRes),
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.size(12.dp),
                            )
                            Text(
                                text = typeLabel,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DetailsRelationSectionContainer(
    modifier: Modifier = Modifier,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    GlassSurface(
        modifier = modifier,
        style = GlassDefaults.subtleStyle(),
        shape = RoundedRectangle(24.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            content = content,
        )
    }
}

@Composable
private fun DetailsRelationItemCard(
    width: androidx.compose.ui.unit.Dp,
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    supportingText: String? = null,
    footer: (@Composable () -> Unit)? = null,
    cover: @Composable () -> Unit,
) {
    GlassSurface(
        modifier = modifier
            .width(width)
            .clickable(onClick = onClick),
        style = GlassDefaults.subtleStyle(),
        shape = RoundedRectangle(22.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            cover()
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp, vertical = 2.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                subtitle?.takeIf { it.isNotBlank() }?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                supportingText?.takeIf { it.isNotBlank() }?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                footer?.invoke()
            }
        }
    }
}

private fun entityRelationSectionIconRes(titleRes: Int): Int = when (titleRes) {
    R.string.entity_graph_section_characters -> R.drawable.ic_user
    R.string.entity_graph_section_creators -> R.drawable.ic_auto_fix
    R.string.entity_graph_section_parent_work -> R.drawable.ic_content_manga
    R.string.entity_graph_section_voice_actors -> R.drawable.ic_voice_input
    R.string.entity_graph_section_created_works -> R.drawable.ic_content_manga
    R.string.entity_graph_section_voiced_characters -> R.drawable.ic_user
    R.string.entity_graph_section_voiced_works -> R.drawable.ic_content_manga
    R.string.entity_graph_section_related_entities -> R.drawable.ic_select_group
    else -> R.drawable.ic_select_group
}

private fun entityRelationTypeLabelRes(type: org.skepsun.kototoro.entitygraph.domain.EntityType): Int = when (type) {
    org.skepsun.kototoro.entitygraph.domain.EntityType.WORK -> R.string.entity_graph_type_work
    org.skepsun.kototoro.entitygraph.domain.EntityType.CHARACTER -> R.string.entity_graph_type_character
    org.skepsun.kototoro.entitygraph.domain.EntityType.PERSON -> R.string.entity_graph_type_person
    org.skepsun.kototoro.entitygraph.domain.EntityType.ORGANIZATION -> R.string.entity_graph_type_organization
}

private fun entityRelationTypeIconRes(type: org.skepsun.kototoro.entitygraph.domain.EntityType): Int = when (type) {
    org.skepsun.kototoro.entitygraph.domain.EntityType.WORK -> R.drawable.ic_content_manga
    org.skepsun.kototoro.entitygraph.domain.EntityType.CHARACTER -> R.drawable.ic_user
    org.skepsun.kototoro.entitygraph.domain.EntityType.PERSON -> R.drawable.ic_user
    org.skepsun.kototoro.entitygraph.domain.EntityType.ORGANIZATION -> R.drawable.ic_select_group
}

@Preview(showBackground = true)
@Composable
private fun EntityRelationCardPreview() {
    KototoroTheme {
        EntityRelationCard(
            item = EntityRelationItem(
                stableKey = "preview-1",
                name = "Kototoro",
                coverUrl = null,
                type = org.skepsun.kototoro.entitygraph.domain.EntityType.WORK,
                subtitle = "Fork of Kotatsu",
                supportingText = "Manga · Ongoing",
            ),
            onClick = {},
        )
    }
}

