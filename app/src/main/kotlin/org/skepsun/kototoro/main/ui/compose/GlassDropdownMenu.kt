package org.skepsun.kototoro.main.ui.compose

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import org.skepsun.kototoro.core.ui.glass.GlassDefaults
import org.skepsun.kototoro.core.ui.glass.GlassStyle
import org.skepsun.kototoro.core.ui.glass.GlassSurface

@Composable
fun GlassDropdownMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    offset: DpOffset = DpOffset(0.dp, 0.dp),
    shape: RoundedCornerShape = RoundedCornerShape(20.dp),
    style: GlassStyle = GlassDefaults.prominentStyle(),
    content: @Composable ColumnScope.() -> Unit,
) {
    val scrollState = rememberScrollState()
    val configuration = LocalConfiguration.current
    val maxMenuHeight = remember(configuration.screenHeightDp) {
        (configuration.screenHeightDp.dp * 0.7f).coerceAtLeast(360.dp)
    }

    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismissRequest,
        modifier = modifier,
        offset = offset,
        shape = shape,
        containerColor = Color.Transparent,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
        border = null,
    ) {
        GlassSurface(
            modifier = Modifier
                .heightIn(max = maxMenuHeight)
                .wrapContentWidth(),
            shape = shape,
            style = style,
            dialogSurface = true,
        ) {
            Column(
                modifier = Modifier
                    .verticalScroll(scrollState)
                    .padding(vertical = 8.dp),
                content = content,
            )
        }
    }
}
