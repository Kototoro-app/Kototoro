package org.skepsun.kototoro.reader.ui.compose

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import androidx.annotation.ColorInt
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix as ComposeColorMatrix
import org.skepsun.kototoro.core.prefs.ReaderBackground
import org.skepsun.kototoro.reader.ui.pager.ReaderAutoBackground
import org.skepsun.kototoro.reader.domain.ReaderColorFilter

@ColorInt
internal fun resolveComposeReaderBackground(
	background: ReaderBackground,
	context: Context,
	@ColorInt themeBackground: Int,
): Int = when (background) {
	ReaderBackground.DEFAULT,
	ReaderBackground.AUTO -> themeBackground
	ReaderBackground.WHITE -> Color.WHITE
	ReaderBackground.BLACK -> Color.BLACK
	ReaderBackground.LIGHT,
	ReaderBackground.DARK -> (background.resolve(context) as? ColorDrawable)?.color
		?: if (background == ReaderBackground.LIGHT) Color.WHITE else Color.BLACK
}

internal fun ReaderColorFilter?.toComposeColorFilter(): ColorFilter? {
	if (this == null || isEmpty) return null
	return ColorFilter.colorMatrix(ComposeColorMatrix(toColorMatrix().array.copyOf()))
}

@ColorInt
internal fun applyAutomaticBookBackgroundTint(
	@ColorInt resolvedColor: Int,
	@ColorInt bookBackgroundTint: Int?,
): Int = if (resolvedColor == Color.WHITE) bookBackgroundTint ?: resolvedColor else resolvedColor

@ColorInt
internal fun resolveDoublePageBackground(
	background: ReaderBackground,
	@ColorInt configuredColor: Int,
	firstAutoColor: Int?,
	secondAutoColor: Int?,
): Int {
	if (background != ReaderBackground.AUTO) return configuredColor
	return when {
		firstAutoColor != null -> ReaderAutoBackground.merge(firstAutoColor, secondAutoColor)
		secondAutoColor != null -> secondAutoColor
		else -> configuredColor
	}
}
