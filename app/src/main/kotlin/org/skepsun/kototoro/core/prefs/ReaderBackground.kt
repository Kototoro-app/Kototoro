package org.skepsun.kototoro.core.prefs

import android.content.Context
import android.graphics.drawable.Drawable
import android.view.ContextThemeWrapper
import androidx.annotation.Keep
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toDrawable
import org.skepsun.kototoro.core.util.ext.getThemeDrawable
import org.skepsun.kototoro.core.util.ext.isNightMode
import com.google.android.material.R as materialR

@Keep
enum class ReaderBackground {

	DEFAULT, LIGHT, DARK, WHITE, BLACK, AUTO;

	fun resolve(context: Context): Drawable? = when (this) {
		DEFAULT -> context.getThemeDrawable(android.R.attr.windowBackground)
		LIGHT -> ContextThemeWrapper(context, materialR.style.ThemeOverlay_Material3_Light)
			.getThemeDrawable(android.R.attr.windowBackground)

		DARK -> ContextThemeWrapper(context, materialR.style.ThemeOverlay_Material3_Dark)
			.getThemeDrawable(android.R.attr.windowBackground)

		WHITE -> ContextCompat.getColor(context, android.R.color.white).toDrawable()
		BLACK -> ContextCompat.getColor(context, android.R.color.black).toDrawable()
		AUTO -> context.getThemeDrawable(android.R.attr.windowBackground)
	}

	fun isLight(context: Context): Boolean = when (this) {
		DEFAULT,
		AUTO -> !context.resources.isNightMode

		LIGHT,
		WHITE -> true

		DARK,
		BLACK -> false
	}
}
