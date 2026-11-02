package org.skepsun.kototoro.list.ui.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.skepsun.kototoro.core.ui.adaptive.TvTextButton

/*
 * The non-content states of a content list, as Android's list renders them (`ErrorStateCard`, `LoadingStateItem`):
 * a centred error with retry and an optional secondary action, and grey skeleton rows while the first page loads.
 * Hosts bring the texts, the icon and what retry means.
 */

/** Android's error card: icon, "an error occurred", the cause, a retry button and an optional secondary action. */
@Composable
fun ContentCollectionError(
    title: String,
    message: String,
    modifier: Modifier = Modifier,
    icon: (@Composable () -> Unit)? = null,
    retryLabel: String? = null,
    onRetry: () -> Unit = {},
    secondaryLabel: String? = null,
    onSecondary: () -> Unit = {},
    enabled: Boolean = true,
    retryModifier: Modifier = Modifier,
    secondaryModifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        icon?.invoke()
        Text(text = title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        if (retryLabel != null) {
            Button(onClick = onRetry, enabled = enabled, modifier = retryModifier) { Text(retryLabel) }
        }
        if (secondaryLabel != null) {
            TvTextButton(onClick = onSecondary, enabled = enabled, modifier = secondaryModifier) { Text(secondaryLabel) }
        }
    }
}

/**
 * One row of poster skeletons while the first page loads: [columns] cells of the grid's own poster ratio, with two
 * title lines under each when the cards print their titles below the poster.
 */
@Composable
fun ContentGridLoadingSkeleton(
    columns: Int,
    posterAspectRatio: Float,
    modifier: Modifier = Modifier,
    spacing: Dp = 8.dp,
    contentPadding: PaddingValues = PaddingValues(vertical = 8.dp),
    titleBelowPoster: Boolean = false,
) {
    Row(
        modifier = modifier.fillMaxWidth().padding(contentPadding),
        horizontalArrangement = Arrangement.spacedBy(spacing),
    ) {
        repeat(columns.coerceAtLeast(1)) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ContentSkeletonBlock(Modifier.fillMaxWidth().aspectRatio(posterAspectRatio))
                if (titleBelowPoster) {
                    ContentSkeletonBlock(Modifier.fillMaxWidth(0.92f).height(12.dp))
                    ContentSkeletonBlock(Modifier.fillMaxWidth(0.68f).height(12.dp))
                }
            }
        }
    }
}

/** Three row skeletons of the one-work-per-row list; [detailed] adds the taller cover and a fourth line. */
@Composable
fun ContentListLoadingSkeleton(
    detailed: Boolean,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(vertical = 8.dp),
) {
    Column(
        modifier = modifier.fillMaxWidth().padding(contentPadding),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        repeat(3) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.Top,
            ) {
                ContentSkeletonBlock(
                    Modifier.width(if (detailed) 96.dp else 84.dp).height(if (detailed) 132.dp else 116.dp),
                )
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    ContentSkeletonBlock(Modifier.fillMaxWidth(0.72f).height(14.dp))
                    ContentSkeletonBlock(Modifier.fillMaxWidth(0.92f).height(12.dp))
                    ContentSkeletonBlock(Modifier.fillMaxWidth(0.84f).height(12.dp))
                    if (detailed) ContentSkeletonBlock(Modifier.fillMaxWidth(0.58f).height(12.dp))
                }
            }
        }
    }
}

/** A grey placeholder in the theme's medium shape. */
@Composable
fun ContentSkeletonBlock(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.background(
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.72f),
            shape = MaterialTheme.shapes.medium,
        ),
    )
}
