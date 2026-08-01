package org.skepsun.kototoro.tracker.ui.debug

import android.graphics.Color
import android.text.format.DateUtils
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.Icons
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.ui.compose.CompactContentCoverShape
import org.skepsun.kototoro.core.util.ext.getThemeColor
import org.skepsun.kototoro.tracker.data.TrackEntity
import androidx.appcompat.R as appcompatR

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun TrackerDebugScreen(
    items: List<TrackDebugItem>,
    onNavigateUp: () -> Unit,
    onItemClick: (TrackDebugItem) -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(text = LocalContext.current.getString(R.string.tracker_debug_info)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateUp) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 8.dp,
                top = padding.calculateTopPadding() + 8.dp,
                end = 8.dp,
                bottom = padding.calculateBottomPadding() + 8.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(0.dp),
        ) {
            items(items, key = { it.manga.id }) { item ->
                TrackerDebugRow(item = item, onClick = { onItemClick(item) })
            }
        }
    }
}

@Composable
private fun TrackerDebugRow(item: TrackDebugItem, onClick: () -> Unit) {
    val context = LocalContext.current
    val summary = buildAnnotatedString {
        append(
            item.lastCheckTime?.let {
                DateUtils.getRelativeDateTimeString(
                    context,
                    it.toEpochMilli(),
                    DateUtils.MINUTE_IN_MILLIS,
                    DateUtils.WEEK_IN_MILLIS,
                    0,
                )
            } ?: context.getString(R.string.never),
        )
        if (item.lastResult == TrackEntity.RESULT_FAILED) {
            append(" - ")
            withStyle(
                SpanStyle(
                    color = ComposeColor(context.getThemeColor(appcompatR.attr.colorError, Color.RED)),
                    fontWeight = FontWeight.Bold,
                ),
            ) {
                append(item.lastError ?: context.getString(R.string.error))
            }
        }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AsyncImage(
            model = item.manga.coverUrl,
            contentDescription = item.manga.title,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(40.dp)
                .clip(CompactContentCoverShape),
        )
        Spacer(modifier = Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = item.manga.title,
                    modifier = Modifier.weight(1f, fill = false),
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                )
                if (item.newChapters > 0) {
                    Icon(
                        painter = painterResource(R.drawable.ic_new),
                        contentDescription = null,
                        modifier = Modifier.padding(start = 8.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            Text(
                text = summary,
                modifier = Modifier.padding(top = 2.dp),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}
