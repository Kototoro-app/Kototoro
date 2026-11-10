package org.skepsun.kototoro.main.ui.compose

import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.ui.adaptive.tvDownFocusLink
import org.skepsun.kototoro.core.ui.adaptive.tvFocusable
import org.skepsun.kototoro.core.ui.compose.FilterFacetOption
import org.skepsun.kototoro.core.ui.compose.SingleChoiceFilterMenu
import org.skepsun.kototoro.parsers.model.ContentType

@Composable
fun ReaderFormatFilter(
    selectedType: ContentType?,
    enabledTypes: Set<ContentType>,
    onTypeSelected: (ContentType?) -> Unit,
    controlSize: Dp = 48.dp,
    iconSize: Dp = 20.dp,
    modifier: Modifier = Modifier,
    downFocusLink: FocusRequester? = null,
) {
    val types = listOf(null, ContentType.MANGA, ContentType.NOVEL)
    val labels = listOf(R.string.all, R.string.content_type_manga, R.string.content_type_novel)
        .map { stringResource(it) }
    val icons = listOf(
        R.drawable.ic_filter_content_type,
        R.drawable.ic_content_manga,
        R.drawable.ic_content_novel,
    )
    val normalizedType = when (selectedType) {
        ContentType.MANGA, ContentType.HENTAI_MANGA,
        ContentType.MANHWA, ContentType.MANHUA -> ContentType.MANGA
        ContentType.NOVEL, ContentType.HENTAI_NOVEL -> ContentType.NOVEL
        else -> null
    }
    val selectedIndex = types.indexOf(normalizedType).coerceAtLeast(0)
    val enabled = enabledTypes.any { it == ContentType.MANGA || it == ContentType.NOVEL }
    SingleChoiceFilterMenu(
        options = types.mapIndexed { index, type ->
            FilterFacetOption(type, labels[index], type == normalizedType, type == null || type in enabledTypes)
        },
        onOptionSelected = onTypeSelected,
        modifier = modifier,
        enabled = enabled,
    ) { openMenu ->
        IconButton(
            onClick = openMenu,
            enabled = enabled,
            modifier = Modifier
                .size(controlSize.coerceAtLeast(48.dp))
                .tvFocusable(enabled = enabled, shape = CircleShape, addFocusTarget = false)
                .tvDownFocusLink(downFocusLink),
        ) {
            Icon(
                painter = painterResource(icons[selectedIndex]),
                contentDescription = stringResource(R.string.content_type_filter) + ": " + labels[selectedIndex],
                modifier = Modifier.size(iconSize),
            )
        }
    }
}
