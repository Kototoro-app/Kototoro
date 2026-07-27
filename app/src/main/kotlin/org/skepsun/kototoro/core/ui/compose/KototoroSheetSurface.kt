package org.skepsun.kototoro.core.ui.compose

import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.skepsun.kototoro.core.ui.glass.GlassComponentRole
import org.skepsun.kototoro.core.ui.glass.GlassDefaults
import org.skepsun.kototoro.core.ui.glass.GlassStyle
import org.skepsun.kototoro.core.ui.glass.GlassSurface

/**
 * Stable Sheet surface shared by feature-owned ModalBottomSheet content.
 *
 * The feature remains responsible for sheet state, outer margins, drag handle,
 * scrolling, and content padding so this wrapper does not change layout behavior.
 */
@Composable
fun KototoroSheetSurface(
	modifier: Modifier = Modifier,
	style: GlassStyle = GlassDefaults.prominentStyle(),
	content: @Composable BoxScope.() -> Unit,
) {
	GlassSurface(
		modifier = modifier,
		shape = RoundedCornerShape(28.dp),
		style = style,
		dialogSurface = true,
		componentRole = GlassComponentRole.Sheet,
		content = content,
	)
}
