package org.skepsun.kototoro.local.ui.compose

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.ui.adaptive.tvChipRing
import org.skepsun.kototoro.parsers.model.ContentTag

@Composable
fun LocalContentTagFilterBar(
    availableTags: Set<ContentTag>,
    selectedTagKeys: Set<String>,
    onTagToggle: (ContentTag) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (availableTags.size < 2) return

    val sortedTags = availableTags.sortedBy { it.title }
    LazyRow(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 4.dp, bottom = 8.dp),
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(
            count = sortedTags.size,
            key = { index -> sortedTags[index].key },
        ) { index ->
            val tag = sortedTags[index]
            val isSelected = tag.key in selectedTagKeys
            FilterChip(
                selected = isSelected,
                onClick = { onTagToggle(tag) },
                leadingIcon = {
                    Icon(
                        painter = painterResource(R.drawable.ic_tag),
                        contentDescription = null,
                    )
                },
                label = {
                    Text(text = tag.title)
                },
                modifier = Modifier.tvChipRing(),
            )
        }
    }
}
