package org.skepsun.kototoro.core.ui.preview

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** Android's Material preview surface also supplies the correct content color to shared text/icons. */
@Composable
fun TabletPreviewSurface(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Surface(modifier, shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shadowElevation = 8.dp, content = content)
}
