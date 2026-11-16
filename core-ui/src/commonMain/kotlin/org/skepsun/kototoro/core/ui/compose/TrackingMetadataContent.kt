package org.skepsun.kototoro.core.ui.compose

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.shape.RoundedCornerShape
import org.skepsun.kototoro.core.ui.adaptive.tvFocusable
import org.skepsun.kototoro.core.ui.buttons.FilledTonalButton
import org.skepsun.kototoro.core.ui.buttons.TextButton
import org.skepsun.kototoro.core.ui.text.uiString

/** Viewing a slot never selects its metadata. Hosts persist only explicit actions. */
data class TrackingMetadataSlot(
    val key: String,
    val label: String,
    val title: String?,
    val usesMetadata: Boolean,
    val isOriginal: Boolean = false,
    val authorized: Boolean = false,
    val editable: Boolean = false,
    val communityScore: String? = null,
    val status: Int? = null,
    val rating: Float = 0f,
    val progress: Int = 0,
)

@Composable
fun TrackingMetadataContent(
    slots: List<TrackingMetadataSlot>,
    selectedKey: String?,
    statuses: List<String>,
    busy: Boolean,
    searching: Boolean,
    onSelectSlot: (TrackingMetadataSlot) -> Unit,
    onUseMetadata: (TrackingMetadataSlot) -> Unit,
    onSearch: (TrackingMetadataSlot) -> Unit,
    onRemove: (TrackingMetadataSlot) -> Unit,
    onSave: (TrackingMetadataSlot, Int, Float, Int?) -> Unit,
    modifier: Modifier = Modifier,
    headerModifier: Modifier = Modifier,
    searchContent: @Composable () -> Unit = {},
) {
    val selected = slots.firstOrNull { it.key == selectedKey } ?: slots.firstOrNull()
    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(bottom = 20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Column(headerModifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(uiString("details_tracking_metadata"), style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold)
                Text(uiString("details_tracking_metadata_hint"), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        item {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(slots, key = TrackingMetadataSlot::key) { slot ->
                    val active = slot.key == selected?.key
                    Surface(
                        onClick = { onSelectSlot(slot) },
                        enabled = !busy,
                        modifier = Modifier.width(156.dp).testTag("tracking-slot:${slot.key}")
                            .tvFocusable(shape = RoundedCornerShape(18.dp), addFocusTarget = false),
                        shape = RoundedCornerShape(18.dp),
                        color = if (active) MaterialTheme.colorScheme.secondaryContainer
                            else MaterialTheme.colorScheme.surfaceContainer,
                        border = if (active) BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = .5f))
                            else null,
                    ) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(slot.label, style = MaterialTheme.typography.labelMedium,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(slot.title ?: uiString("details_tracking_unlinked"),
                                style = MaterialTheme.typography.bodyMedium, maxLines = 2,
                                minLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(
                                uiString(when {
                                    slot.usesMetadata -> "details_tracking_metadata_active"
                                    slot.isOriginal -> "details_tracking_original"
                                    slot.title != null -> "details_tracking_linked"
                                    else -> "details_tracking_search_slot"
                                }),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                                maxLines = 1,
                            )
                        }
                    }
                }
            }
        }
        if (busy) item { LinearProgressIndicator(modifier = Modifier.fillMaxWidth()) }
        selected?.let { slot ->
            item(key = "selected:${slot.key}") {
                Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
                    Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(slot.label, style = MaterialTheme.typography.titleSmall)
                            slot.communityScore?.let {
                                Text(uiString("details_tracking_community_score", it),
                                    style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                            }
                        }
                        if (slot.title != null) {
                            Text(slot.title, style = MaterialTheme.typography.bodyMedium, maxLines = 2,
                                overflow = TextOverflow.Ellipsis)
                            FilledTonalButton(
                                onClick = { onUseMetadata(slot) }, enabled = !busy && !slot.usesMetadata,
                                modifier = Modifier.fillMaxWidth().testTag("tracking-use-metadata"),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                            ) {
                                Text(uiString(if (slot.usesMetadata) "details_tracking_metadata_active"
                                    else "details_tracking_use_metadata"))
                            }
                        }
                        if (slot.editable) {
                            TrackingProgressEditor(slot, statuses, busy, onSave)
                        } else if (!slot.isOriginal) {
                            Text(uiString(if (slot.authorized) "details_tracking_link_hint"
                                else "details_metadata_source_login_hint"),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (!slot.isOriginal) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                TextButton(onClick = { onSearch(slot) }, enabled = !busy) {
                                    Text(uiString(if (slot.title == null) "search" else "details_tracking_replace"))
                                }
                                if (slot.title != null) TextButton(onClick = { onRemove(slot) }, enabled = !busy) {
                                    Text(uiString("details_tracking_unlink"), color = MaterialTheme.colorScheme.error)
                                }
                            }
                        }
                    }
                }
            }
        }
        if (searching) item { searchContent() }
    }
}

@Composable
private fun TrackingProgressEditor(
    slot: TrackingMetadataSlot,
    statuses: List<String>,
    busy: Boolean,
    onSave: (TrackingMetadataSlot, Int, Float, Int?) -> Unit,
) {
    var progress by rememberSaveable(slot.key, slot.progress) { mutableStateOf(slot.progress.toString()) }
    var rating by rememberSaveable(slot.key, slot.rating) { mutableStateOf((slot.rating * 10f).toString()) }
    var status by rememberSaveable(slot.key, slot.status) { mutableStateOf(slot.status) }
    val parsedProgress = progress.toIntOrNull()?.takeIf { it >= 0 }
    val parsedRating = rating.toFloatOrNull()?.takeIf { it.isFinite() && it in 0f..10f }
    val changed = parsedProgress != slot.progress || parsedRating != slot.rating * 10f || status != slot.status
    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        items(statuses.size) { index ->
            FilterChip(selected = status == index, onClick = { status = index }, enabled = !busy,
                label = { Text(statuses[index]) })
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(value = progress, onValueChange = { progress = it }, enabled = !busy,
            label = { Text(uiString("details_tracking_progress")) }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            isError = parsedProgress == null, modifier = Modifier.weight(1f).testTag("tracking-progress"),
            shape = RoundedCornerShape(14.dp))
        OutlinedTextField(value = rating, onValueChange = { rating = it }, enabled = !busy,
            label = { Text(uiString("details_tracking_personal_score")) }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            isError = parsedRating == null, modifier = Modifier.weight(1f).testTag("tracking-rating"),
            shape = RoundedCornerShape(14.dp))
    }
    FilledTonalButton(
        onClick = { onSave(slot, requireNotNull(parsedProgress), requireNotNull(parsedRating) / 10f, status) },
        enabled = !busy && changed && parsedProgress != null && parsedRating != null,
        modifier = Modifier.fillMaxWidth().testTag("tracking-save"),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
    ) { Text(uiString("save")) }
}
