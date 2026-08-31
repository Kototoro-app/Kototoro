package org.skepsun.kototoro.main.ui.welcome

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.ui.compose.rememberSafePainter
import org.skepsun.kototoro.core.util.ext.getDisplayName
import java.util.Locale

/**
 * Unified header for every setup-wizard page: a tinted icon, the current
 * step counter, the page title and a one-line summary. The first page uses
 * the enlarged [prominent] variant.
 */
@Composable
internal fun WizardPageHeader(
    step: Int,
    totalSteps: Int,
    title: String,
    summary: String,
    icon: Painter,
    prominent: Boolean = false,
    containerColor: Color = MaterialTheme.colorScheme.primaryContainer,
    contentColor: Color = MaterialTheme.colorScheme.onPrimaryContainer,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Surface(
            shape = RoundedCornerShape(if (prominent) 22.dp else 16.dp),
            color = containerColor,
            contentColor = contentColor,
        ) {
            Icon(
                painter = icon,
                contentDescription = null,
                modifier = Modifier
                    .padding(if (prominent) 14.dp else 12.dp)
                    .size(if (prominent) 30.dp else 24.dp),
            )
        }
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = stringResource(R.string.welcome_step_counter, step, totalSteps),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            AnimatedContent(
                targetState = title,
                transitionSpec = {
                    (
                        slideInVertically { it / 2 } + fadeIn() togetherWith
                            slideOutVertically { -it / 2 } + fadeOut()
                        )
                },
                label = "wizardTitle",
            ) { animatedTitle ->
                Text(
                    text = animatedTitle,
                    style = if (prominent) {
                        MaterialTheme.typography.headlineMedium
                    } else {
                        MaterialTheme.typography.headlineSmall
                    },
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            Text(
                text = summary,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Unified content container for wizard body sections. Material container
 * styling only — the glass finish is reserved for the floating bottom bar.
 */
@Composable
internal fun WizardSectionCard(
    modifier: Modifier = Modifier,
    title: String? = null,
    summary: String? = null,
    actions: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (title != null || actions != null) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        if (title != null) {
                            Text(
                                text = title,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                        if (summary != null) {
                            Text(
                                text = summary,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    actions?.invoke()
                }
            } else if (summary != null) {
                Text(
                    text = summary,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            content()
        }
    }
}

/**
 * Collapsible repository-type group. Shows the selected / total count even
 * when collapsed; the repos inside are plain multi-select list rows, not
 * filter chips.
 */
@Composable
internal fun RepoKindGroupCard(
    title: String,
    selectedCount: Int,
    totalCount: Int,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
    enabled: Boolean,
    actions: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    WizardSectionCard {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .toggleable(
                    value = expanded,
                    enabled = enabled,
                    role = Role.Button,
                    onValueChange = { onToggleExpanded() },
                ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = stringResource(R.string.welcome_repos_selected_count, selectedCount, totalCount),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Icon(
                imageVector = if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
        }
        Column(modifier = Modifier.animateContentSize()) {
            if (expanded) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    actions?.let { actionsContent ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End,
                        ) {
                            actionsContent()
                        }
                    }
                    content()
                }
            }
        }
    }
}

/**
 * Compact repository multi-select row. Uses an explicit check affordance so
 * the tap semantics ("select") are never confused with expand/filter chips.
 */
@Composable
internal fun RepoCheckRow(
    title: String,
    subtitle: String?,
    selected: Boolean,
    enabled: Boolean,
    onToggle: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .toggleable(
                value = selected,
                enabled = enabled,
                role = Role.Checkbox,
                onValueChange = { onToggle() },
            )
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        WizardCheckIndicator(selected = selected)
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** Small circular check affordance used by [RepoCheckRow]. */
@Composable
private fun WizardCheckIndicator(selected: Boolean) {
    Surface(
        shape = CircleShape,
        color = if (selected) MaterialTheme.colorScheme.primary else Color.Transparent,
        border = if (selected) {
            null
        } else {
            androidx.compose.foundation.BorderStroke(2.dp, MaterialTheme.colorScheme.outline)
        },
        modifier = Modifier.size(22.dp),
    ) {
        if (selected) {
            Icon(
                imageVector = Icons.Default.Check,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.padding(4.dp),
            )
        }
    }
}

/** Progress indicator that always carries a human-readable label. */
@Composable
internal fun WizardInlineStatus(
    text: String,
    modifier: Modifier = Modifier,
    progress: (() -> Float)? = null,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (progress != null) {
            LinearProgressIndicator(progress = progress, modifier = Modifier.fillMaxWidth())
        } else {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Leading status icon for one install-list row. */
@Composable
internal fun WizardPackageStatusIcon(state: WizardPackageState, modifier: Modifier = Modifier) {
    when (state) {
        WizardPackageState.DOWNLOADING -> CircularProgressIndicator(
            modifier = modifier.size(20.dp),
            strokeWidth = 2.dp,
        )
        WizardPackageState.INSTALLING -> CircularProgressIndicator(
            modifier = modifier.size(20.dp),
            strokeWidth = 2.dp,
            color = MaterialTheme.colorScheme.tertiary,
        )
        else -> {
            val (icon, tint) = when (state) {
                WizardPackageState.QUEUED ->
                    rememberSafePainter(R.drawable.ic_schedule) to MaterialTheme.colorScheme.onSurfaceVariant
                WizardPackageState.COMPLETED ->
                    rememberSafePainter(R.drawable.ic_check) to MaterialTheme.colorScheme.primary
                WizardPackageState.FAILED ->
                    rememberSafePainter(R.drawable.ic_error_small) to MaterialTheme.colorScheme.error
                WizardPackageState.CANCELLED ->
                    rememberSafePainter(R.drawable.ic_cancel_multiple) to MaterialTheme.colorScheme.outline
                else -> rememberSafePainter(R.drawable.ic_schedule) to MaterialTheme.colorScheme.onSurfaceVariant
            }
            Icon(
                painter = icon,
                contentDescription = stringResource(state.labelResId()),
                tint = tint,
                modifier = modifier.size(20.dp),
            )
        }
    }
}

/** Crossfade between the install-item states so icon changes are not abrupt. */
@Composable
internal fun WizardPackageStatusIconAnimated(state: WizardPackageState, modifier: Modifier = Modifier) {
    Crossfade(targetState = state, modifier = modifier, label = "packageState") { animatedState ->
        WizardPackageStatusIcon(state = animatedState)
    }
}

/**
 * Collapsed language summary: shows the first few selections plus a count and
 * opens a searchable multi-select panel.
 */
@Composable
internal fun WizardLanguageSelector(
    locales: org.skepsun.kototoro.filter.ui.model.FilterProperty<Locale>,
    enabled: Boolean,
    onLocaleToggle: (Locale, Boolean) -> Unit,
    onSelectAll: () -> Unit,
    onClearAll: () -> Unit,
) {
    val context = LocalContext.current
    var dialogOpen by rememberSaveable { mutableStateOf(false) }

    val sortedSelected = locales.selectedItems
        .sortedBy { it.getDisplayName(context).lowercase() }
    val summaryText = when {
        sortedSelected.isEmpty() -> null
        sortedSelected.size <= 2 -> sortedSelected.joinToString { it.getDisplayName(context) }
        else -> stringResource(
            R.string.welcome_languages_summary_more,
            sortedSelected.take(2).joinToString { it.getDisplayName(context) },
            sortedSelected.size,
        )
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = summaryText.orEmpty(),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = { dialogOpen = true }, enabled = enabled) {
            Text(stringResource(R.string.welcome_languages_edit))
        }
    }

    if (dialogOpen) {
        WizardLanguagePickerDialog(
            locales = locales,
            onLocaleToggle = onLocaleToggle,
            onSelectAll = onSelectAll,
            onClearAll = onClearAll,
            onDismiss = { dialogOpen = false },
        )
    }
}

@Composable
private fun WizardLanguagePickerDialog(
    locales: org.skepsun.kototoro.filter.ui.model.FilterProperty<Locale>,
    onLocaleToggle: (Locale, Boolean) -> Unit,
    onSelectAll: () -> Unit,
    onClearAll: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    var query by rememberSaveable { mutableStateOf("") }
    val items = locales.availableItems
    val filtered = items.filter { locale ->
        query.isBlank() || locale.getDisplayName(context).contains(query.trim(), ignoreCase = true)
    }
    val multilingual = filtered.filter { it == Locale.ROOT }
    val others = filtered.filter { it != Locale.ROOT }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.welcome_languages_dialog_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text(stringResource(R.string.welcome_languages_search_hint)) },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Default.Search,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                        )
                    },
                    singleLine = true,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(R.string.welcome_languages_selected_count, locales.selectedItems.size),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = onSelectAll) { Text(stringResource(R.string.select_all)) }
                    TextButton(onClick = onClearAll) { Text(stringResource(R.string.clear)) }
                }
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 360.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    if (multilingual.isNotEmpty()) {
                        Text(
                            text = stringResource(R.string.welcome_languages_group_multilingual),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(vertical = 4.dp),
                        )
                        multilingual.forEach { locale ->
                            WizardLanguageRow(
                                locale = locale,
                                selected = locale in locales.selectedItems,
                                onToggle = { onLocaleToggle(locale, locale !in locales.selectedItems) },
                            )
                        }
                    }
                    Text(
                        text = stringResource(R.string.welcome_languages_group_other),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(vertical = 4.dp),
                    )
                    others.forEach { locale ->
                        WizardLanguageRow(
                            locale = locale,
                            selected = locale in locales.selectedItems,
                            onToggle = { onLocaleToggle(locale, locale !in locales.selectedItems) },
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.done)) }
        },
    )
}

@Composable
private fun WizardLanguageRow(
    locale: Locale,
    selected: Boolean,
    onToggle: () -> Unit,
) {
    val context = LocalContext.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .toggleable(value = selected, role = Role.Checkbox, onValueChange = { onToggle() })
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        WizardCheckIndicator(selected = selected)
        Text(
            text = locale.getDisplayName(context),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

/** Small count badge used by the install-status summary header. */
@Composable
internal fun WizardStatChip(text: String, color: Color) {
    Surface(
        shape = RoundedCornerShape(999.dp),
        color = color.copy(alpha = 0.12f),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = color,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            textAlign = TextAlign.Center,
        )
    }
}

/** Keep FilterChip usage for real single/multi filter semantics only. */
@Composable
internal fun WizardFilterChip(
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    label: @Composable () -> Unit,
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        enabled = enabled,
        label = label,
        leadingIcon = if (selected) {
            {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
            }
        } else {
            null
        },
    )
}
