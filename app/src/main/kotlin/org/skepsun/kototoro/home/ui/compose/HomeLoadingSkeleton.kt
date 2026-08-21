package org.skepsun.kototoro.home.ui.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.tooling.preview.Preview
import org.skepsun.kototoro.core.ui.compose.CompactPosterCardStyle
import org.skepsun.kototoro.core.ui.theme.KototoroTheme


@Composable
internal fun HomeLoadingSkeleton(
    posterStyle: org.skepsun.kototoro.core.ui.compose.CompactPosterCardStyle,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 2.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        HomeSkeletonBlock(
            modifier = Modifier
                .fillMaxWidth()
                .height(232.dp),
        )
        repeat(2) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    HomeSkeletonBlock(
                        modifier = Modifier
                            .width(124.dp)
                            .height(16.dp),
                    )
                    HomeSkeletonBlock(
                        modifier = Modifier
                            .width(52.dp)
                            .height(14.dp),
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    repeat(3) {
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            HomeSkeletonBlock(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(posterStyle.posterHeight),
                            )
                            HomeSkeletonBlock(
                                modifier = Modifier
                                    .fillMaxWidth(0.9f)
                                    .height(12.dp),
                            )
                            HomeSkeletonBlock(
                                modifier = Modifier
                                    .fillMaxWidth(0.64f)
                                    .height(12.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HomeSkeletonBlock(
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.78f)),
    )
}

@Preview(showBackground = true)
@Composable
private fun HomeLoadingSkeletonPreview() {
    KototoroTheme {
        HomeLoadingSkeleton(
            posterStyle = CompactPosterCardStyle(
                itemWidth = 84.dp,
                posterHeight = 120.dp,
                cornerRadius = 8.dp,
            ),
        )
    }
}
