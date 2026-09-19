package org.skepsun.kototoro.reader.novel.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.skepsun.kototoro.R
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import org.skepsun.kototoro.reader.novel.annotation.NovelMarkingColor
import org.skepsun.kototoro.reader.novel.annotation.NovelMarkingStyle
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
internal fun NovelTextSelectionActions(
    selection: NovelTextSelection,
    onAction: (NovelTextSelection, NovelTextSelectionAction) -> Unit,
    modifier: Modifier = Modifier,
    isMarking: Boolean = false,
    selectedColor: Int = 0,
    selectedStyle: Int = 0,
    onColorChange: ((Int) -> Unit)? = null,
    onStyleChange: ((Int) -> Unit)? = null,
    note: String? = null,
    updatedAt: Long? = null,
    onExpandNote: (() -> Unit)? = null,
    isAbove: Boolean = true,
    panelColor: Color? = null,
    panelContentColor: Color? = null,
    maxWidth: Dp? = null,
    maxHeight: Dp? = null,
) {
    var moreMenuExpanded by remember { mutableStateOf(false) }
    val verticalScrollState = rememberScrollState()
    val resolvedPanelColor = panelColor ?: MaterialTheme.colorScheme.surfaceContainerHigh
    val resolvedContentColor = panelContentColor ?: MaterialTheme.colorScheme.onSurface
    val resolvedAccentColor = if (onColorChange != null) {
        NovelMarkingColor.fromId(selectedColor).lineColor
    } else {
        MaterialTheme.colorScheme.primary
    }

    Surface(
        modifier = modifier
            .widthIn(max = maxWidth ?: 440.dp)
            .then(maxHeight?.let { Modifier.heightIn(max = it) } ?: Modifier),
        shape = RoundedCornerShape(18.dp),
        color = resolvedPanelColor,
        contentColor = resolvedContentColor,
        tonalElevation = 2.dp,
        shadowElevation = 10.dp,
    ) {
        Column(
            modifier = Modifier
                .then(maxHeight?.let { Modifier.verticalScroll(verticalScrollState) } ?: Modifier)
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            val paletteRow = @Composable {
                if (onColorChange != null && onStyleChange != null) {
                    HorizontalDivider(
                        color = resolvedContentColor.copy(alpha = 0.12f),
                        modifier = Modifier.padding(horizontal = 4.dp),
                    )
                    NovelMarkingPaletteRow(
                        selectedColor = selectedColor,
                        selectedStyle = selectedStyle,
                        onColorSelect = onColorChange,
                        onStyleSelect = onStyleChange,
                        contentColor = resolvedContentColor,
                    )
                }
            }

            val actionButtons = @Composable {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 4.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    val primaryActions = novelPrimaryActions(isMarking)
                    primaryActions.forEach { action ->
                        SelectionActionButton(
                            selection = selection,
                            action = action,
                            isMarking = isMarking,
                            hasNote = !note.isNullOrBlank(),
                            contentColor = resolvedContentColor,
                            accentColor = resolvedAccentColor,
                            onAction = onAction,
                        )
                    }

                    Box {
                        IconButton(
                            onClick = { moreMenuExpanded = true },
                            modifier = Modifier.size(36.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Default.MoreVert,
                                contentDescription = stringResource(R.string.options),
                                modifier = Modifier.size(18.dp),
                                tint = resolvedContentColor.copy(alpha = 0.8f),
                            )
                        }
                        DropdownMenu(
                            expanded = moreMenuExpanded,
                            onDismissRequest = { moreMenuExpanded = false },
                        ) {
                            novelOverflowActions().forEach { action ->
                                DropdownMenuItem(
                                    text = { Text(actionLabel(action)) },
                                    onClick = {
                                        moreMenuExpanded = false
                                        onAction(selection, action)
                                    },
                                )
                            }
                        }
                    }
                }
            }

            val thoughtCard = @Composable {
                if (!note.isNullOrBlank()) {
                    NovelThoughtCard(
                        note = note,
                        updatedAt = updatedAt,
                        contentColor = resolvedContentColor,
                        accentColor = resolvedAccentColor,
                        onEdit = { onAction(selection, NovelTextSelectionAction.NOTE) },
                        onExpand = onExpandNote,
                    )
                }
            }

            if (isAbove) {
                thoughtCard()
                paletteRow()
                actionButtons()
            } else {
                paletteRow()
                actionButtons()
                thoughtCard()
            }
        }
    }
}

@Composable
private fun NovelMarkingPaletteRow(
    selectedColor: Int,
    selectedStyle: Int,
    onColorSelect: (Int) -> Unit,
    onStyleSelect: (Int) -> Unit,
    contentColor: Color,
    modifier: Modifier = Modifier,
) {
    val currentColor = remember(selectedColor) { NovelMarkingColor.fromId(selectedColor) }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 6.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        // 3 Styles: Straight, Wavy, Marker
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            NovelMarkingStyle.entries.forEach { styleOption ->
                val isSelected = styleOption.id == selectedStyle
                MarkingStyleButton(
                    style = styleOption,
                    color = currentColor,
                    isSelected = isSelected,
                    contentColor = contentColor,
                    onClick = { onStyleSelect(styleOption.id) },
                )
            }
        }

        // Subtle vertical divider
        Box(
            modifier = Modifier
                .width(1.dp)
                .height(18.dp)
                .background(contentColor.copy(alpha = 0.18f))
        )

        // 5 Colors: Yellow, Green, Blue, Pink, Orange
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            NovelMarkingColor.entries.forEach { colorOption ->
                val isSelected = colorOption.id == selectedColor
                MarkingColorChip(
                    color = colorOption,
                    isSelected = isSelected,
                    onClick = { onColorSelect(colorOption.id) },
                )
            }
        }
    }
}

@Composable
private fun MarkingStyleButton(
    style: NovelMarkingStyle,
    color: NovelMarkingColor,
    isSelected: Boolean,
    contentColor: Color,
    onClick: () -> Unit,
) {
    val tint = if (isSelected) color.lineColor else contentColor.copy(alpha = 0.72f)
    val bg = if (isSelected) color.bgColor else androidx.compose.ui.graphics.Color.Transparent
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(8.dp),
        color = bg,
        modifier = Modifier.size(width = 40.dp, height = 36.dp),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.fillMaxSize(),
        ) {
            when (style) {
                NovelMarkingStyle.UNDERLINE -> {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Text(
                            text = "A",
                            style = MaterialTheme.typography.labelMedium.copy(
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                fontSize = 13.sp,
                            ),
                            color = tint,
                        )
                        Spacer(Modifier.height(1.dp))
                        Box(
                            modifier = Modifier
                                .width(13.dp)
                                .height(2.dp)
                                .clip(CircleShape)
                                .background(tint),
                        )
                    }
                }
                NovelMarkingStyle.WAVY -> {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Text(
                            text = "A",
                            style = MaterialTheme.typography.labelMedium.copy(
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                fontSize = 13.sp,
                            ),
                            color = tint,
                        )
                        Spacer(Modifier.height(1.dp))
                        Canvas(modifier = Modifier.size(width = 13.dp, height = 3.dp)) {
                            val path = Path()
                            path.moveTo(0f, size.height / 2f)
                            path.cubicTo(2.2f, 0f, 4.3f, size.height, 6.5f, size.height / 2f)
                            path.cubicTo(8.7f, 0f, 10.8f, size.height, 13f, size.height / 2f)
                            drawPath(
                                path = path,
                                color = tint,
                                style = Stroke(
                                    width = 1.5.dp.toPx(),
                                    cap = StrokeCap.Round,
                                ),
                            )
                        }
                    }
                }
                NovelMarkingStyle.HIGHLIGHT -> {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(22.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(
                                if (isSelected) color.lineColor.copy(alpha = 0.25f)
                                else contentColor.copy(alpha = 0.08f)
                            ),
                    ) {
                        Text(
                            text = "A",
                            style = MaterialTheme.typography.labelMedium.copy(
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                fontSize = 13.sp,
                            ),
                            color = tint,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MarkingColorChip(
    color: NovelMarkingColor,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(32.dp)
            .clip(CircleShape)
            .then(
                if (isSelected) {
                    Modifier.border(2.dp, color.lineColor, CircleShape)
                } else {
                    Modifier
                }
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(if (isSelected) 18.dp else 22.dp)
                .clip(CircleShape)
                .background(color.lineColor),
        )
    }
}

@Composable
private fun NovelThoughtCard(
    note: String,
    updatedAt: Long?,
    contentColor: Color,
    accentColor: Color,
    onEdit: () -> Unit,
    onExpand: (() -> Unit)?,
) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = contentColor.copy(alpha = 0.06f),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 2.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Box(
                modifier = Modifier
                    .padding(top = 2.dp)
                    .width(3.dp)
                    .height(34.dp)
                    .clip(CircleShape)
                    .background(accentColor),
            )
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(R.string.novel_selection_my_thought),
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                        color = accentColor,
                    )
                    IconButton(
                        onClick = onEdit,
                        modifier = Modifier.size(22.dp),
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_edit),
                            contentDescription = stringResource(R.string.novel_marking_edit_note),
                            modifier = Modifier.size(15.dp),
                            tint = contentColor.copy(alpha = 0.72f),
                        )
                    }
                }
                Text(
                    text = note,
                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp, lineHeight = 20.sp),
                    color = contentColor,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(vertical = 2.dp),
                )
                if (note.length > 50 || note.lines().size > 2) {
                    Text(
                        text = stringResource(R.string.novel_selection_expand_thought),
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                        color = accentColor,
                        modifier = Modifier
                            .clickable { onExpand?.invoke() ?: onEdit() }
                            .padding(vertical = 2.dp),
                    )
                }
                if (updatedAt != null && updatedAt > 0L) {
                    val dateString = remember(updatedAt) {
                        SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(updatedAt))
                    }
                    Text(
                        text = stringResource(R.string.novel_selection_updated_at, dateString),
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                        color = contentColor.copy(alpha = 0.58f),
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }
        }
    }
}

internal fun novelPrimaryActions(isMarking: Boolean): List<NovelTextSelectionAction> = buildList {
    if (!isMarking) {
        add(NovelTextSelectionAction.HIGHLIGHT)
    }
    add(NovelTextSelectionAction.COPY)
    add(NovelTextSelectionAction.NOTE)
    add(NovelTextSelectionAction.EXCERPT)
    add(NovelTextSelectionAction.ASK_AI)
    add(NovelTextSelectionAction.LISTEN)
    if (isMarking) {
        add(NovelTextSelectionAction.DELETE)
    }
}

internal fun novelOverflowActions(): List<NovelTextSelectionAction> = listOf(
    NovelTextSelectionAction.DICTIONARY,
    NovelTextSelectionAction.SHARE,
)

internal fun novelSelectionActions(isMarking: Boolean): List<NovelTextSelectionAction> =
    novelPrimaryActions(isMarking) + novelOverflowActions()

@Composable
private fun actionLabel(
    action: NovelTextSelectionAction,
    isMarking: Boolean = false,
    hasNote: Boolean = false,
): String = when (action) {
    NovelTextSelectionAction.COPY -> stringResource(R.string.copy)
    NovelTextSelectionAction.NOTE -> if (hasNote) {
        stringResource(R.string.novel_marking_edit_note)
    } else {
        stringResource(R.string.novel_selection_write_idea)
    }
    NovelTextSelectionAction.EXCERPT -> stringResource(R.string.novel_selection_excerpt)
    NovelTextSelectionAction.ASK_AI -> stringResource(R.string.novel_selection_ask_ai)
    NovelTextSelectionAction.LISTEN -> stringResource(R.string.novel_selection_listen)
    NovelTextSelectionAction.SHARE -> stringResource(R.string.share)
    NovelTextSelectionAction.DICTIONARY -> stringResource(R.string.novel_selection_dictionary)
    NovelTextSelectionAction.HIGHLIGHT -> if (isMarking) {
        stringResource(R.string.novel_selection_highlighted)
    } else {
        stringResource(R.string.novel_selection_highlight)
    }
    NovelTextSelectionAction.DELETE -> stringResource(R.string.delete)
    NovelTextSelectionAction.BOOKMARK -> error("Bookmark is intentionally not a selection toolbar action")
}

@Composable
private fun SelectionActionButton(
    selection: NovelTextSelection,
    action: NovelTextSelectionAction,
    isMarking: Boolean,
    hasNote: Boolean,
    contentColor: Color,
    accentColor: Color,
    onAction: (NovelTextSelection, NovelTextSelectionAction) -> Unit,
) {
    val label = actionLabel(action, isMarking, hasNote)
    val isDestructive = action == NovelTextSelectionAction.DELETE
    val isPrimary = action == NovelTextSelectionAction.HIGHLIGHT
    val foregroundColor = when {
        isDestructive -> MaterialTheme.colorScheme.error
        isPrimary -> accentColor
        else -> contentColor.copy(alpha = 0.86f)
    }
    val backgroundColor = when {
        isDestructive -> MaterialTheme.colorScheme.error.copy(alpha = 0.08f)
        isPrimary -> accentColor.copy(alpha = 0.14f)
        else -> contentColor.copy(alpha = 0.05f)
    }
    Surface(
        onClick = { onAction(selection, action) },
        shape = RoundedCornerShape(12.dp),
        color = backgroundColor,
        contentColor = foregroundColor,
        modifier = Modifier.height(40.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(
                painter = painterResource(actionIcon(action)),
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = foregroundColor,
            )
            Text(
                text = label,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.labelMedium.copy(
                    fontWeight = if (isPrimary) FontWeight.SemiBold else FontWeight.Medium,
                ),
                color = foregroundColor,
            )
        }
    }
}

private fun actionIcon(action: NovelTextSelectionAction): Int = when (action) {
    NovelTextSelectionAction.COPY -> R.drawable.ic_copy
    NovelTextSelectionAction.SHARE -> R.drawable.ic_share
    NovelTextSelectionAction.DICTIONARY -> R.drawable.ic_search
    NovelTextSelectionAction.BOOKMARK -> R.drawable.ic_bookmark
    NovelTextSelectionAction.HIGHLIGHT -> R.drawable.ic_select_range
    NovelTextSelectionAction.NOTE -> R.drawable.ic_comment
    NovelTextSelectionAction.EXCERPT -> R.drawable.ic_book_page
    NovelTextSelectionAction.ASK_AI -> R.drawable.ic_auto_fix
    NovelTextSelectionAction.LISTEN -> R.drawable.ic_volume_up
    NovelTextSelectionAction.DELETE -> R.drawable.ic_delete
}
