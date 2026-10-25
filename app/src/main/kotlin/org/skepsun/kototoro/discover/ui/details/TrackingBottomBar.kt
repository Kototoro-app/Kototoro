package org.skepsun.kototoro.discover.ui.details

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.ui.adaptive.TvTextButton
import org.skepsun.kototoro.core.ui.glass.GlassBottomBarContainer

@Composable
fun TrackingBottomBar(
    hasLinkedContent: Boolean,
    hasExternalUrl: Boolean,
    canTranslate: Boolean,
    isShowingTranslation: Boolean,
    isTranslating: Boolean,
    onOpenLinked: () -> Unit,
    onOpenLinkedTab: ((String) -> Unit)? = null,
    onManageBinding: () -> Unit,
    onBrowseSources: () -> Unit,
    onOpenExternal: () -> Unit,
    onToggleTranslation: () -> Unit,
    modifier: Modifier = Modifier,
) {
    GlassBottomBarContainer(
        modifier = modifier
            .fillMaxWidth()
            .navigationBarsPadding(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (hasLinkedContent && onOpenLinkedTab != null) {
                // Dock-style buttons mirroring the local content details toolbar
                org.skepsun.kototoro.details.ui.compose.DetailsDockActionButton(
                    iconRes = R.drawable.ic_list,
                    contentDescription = stringResource(R.string.chapters),
                    isSelected = false,
                    onClick = { onOpenLinkedTab("chapters") },
                )
                org.skepsun.kototoro.details.ui.compose.DetailsDockActionButton(
                    iconRes = R.drawable.ic_grid,
                    contentDescription = stringResource(R.string.pages),
                    isSelected = false,
                    onClick = { onOpenLinkedTab("pages") },
                )
                org.skepsun.kototoro.details.ui.compose.DetailsDockActionButton(
                    iconRes = R.drawable.ic_bookmark,
                    contentDescription = stringResource(R.string.bookmarks),
                    isSelected = false,
                    onClick = { onOpenLinkedTab("bookmarks") },
                )
                Spacer(modifier = Modifier.weight(1f))
                Button(
                    onClick = onOpenLinked,
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_book_page),
                        contentDescription = null,
                    )
                    Text(
                        text = stringResource(R.string.read),
                        modifier = Modifier.padding(start = 6.dp),
                    )
                }
                TvTextButton(onClick = onManageBinding) {
                    Text(stringResource(R.string.discover_manage_binding))
                }
            } else if (hasLinkedContent) {
                Button(
                    onClick = onOpenLinked,
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_book_page),
                        contentDescription = null,
                    )
                    Text(
                        text = stringResource(R.string.details),
                        modifier = Modifier.padding(start = 6.dp),
                    )
                }
                TvTextButton(onClick = onManageBinding) {
                    Text(stringResource(R.string.discover_manage_binding))
                }
            } else {
                Button(
                    onClick = onBrowseSources,
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
                ) {
                    Icon(
                        imageVector = Icons.Default.Search,
                        contentDescription = null,
                    )
                    Text(
                        text = stringResource(R.string.search),
                        modifier = Modifier.padding(start = 6.dp),
                    )
                }
            }

            if (canTranslate) {
                TvTextButton(
                    onClick = onToggleTranslation,
                    enabled = !isTranslating,
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = if (isShowingTranslation) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    ),
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_translate),
                        contentDescription = stringResource(R.string.translation),
                    )
                }
            }

            if (hasExternalUrl) {
                TvTextButton(onClick = onOpenExternal) {
                    Icon(
                        painter = painterResource(R.drawable.ic_open_external),
                        contentDescription = stringResource(R.string.open_in_browser),
                    )
                }
            }
        }
    }
}
