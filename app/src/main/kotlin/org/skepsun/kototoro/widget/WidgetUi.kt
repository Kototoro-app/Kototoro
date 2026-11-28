package org.skepsun.kototoro.widget

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceModifier
import androidx.glance.ImageProvider
import androidx.glance.appwidget.cornerRadius
import androidx.glance.background
import androidx.glance.color.ColorProvider
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Row
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.padding
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import org.skepsun.kototoro.R

internal val WidgetPrimaryTextColor = ColorProvider(
    Color(0xFF1D1B20),
    Color(0xFFE6E1E5),
)

internal val WidgetSecondaryTextColor = ColorProvider(
    Color(0xFF49454F),
    Color(0xFFCAC4D0),
)

internal val WidgetAccentTextColor = ColorProvider(
    Color(0xFF6750A4),
    Color(0xFFD0BCFF),
)

// Explicit colours: `?android:panelColorBackground` resolves to black on some launchers, hiding the titles.
internal val WidgetCardColor = ColorProvider(
    Color(0xFFECE6F0),
    Color(0xFF2B2930),
)

/** Root padding on both sides of each axis, matching [widgetRootModifier]. */
internal fun widgetRootPadding(hasBackground: Boolean) = if (hasBackground) 16.dp else 8.dp

/** Height [WidgetHeader] takes, used to size the rows that fit below it. */
internal val WidgetHeaderHeight = 36.dp

/** Glance renders at most 10 children per Column, and the header takes one of them. */
internal fun widgetMaxRows(showHeader: Boolean) = if (showHeader) 9 else 10

internal fun widgetRootModifier(hasBackground: Boolean): GlanceModifier {
    val modifier = GlanceModifier.fillMaxSize()
    return if (hasBackground) {
        modifier
            .background(ImageProvider(R.drawable.bg_appwidget_root))
            .cornerRadius(R.dimen.appwidget_corner_radius_background)
            .padding(8.dp)
    } else {
        modifier.padding(4.dp)
    }
}

@Composable
internal fun WidgetHeader(
    title: String,
    itemCount: Int,
) {
    Row(
        modifier = GlanceModifier
            .fillMaxWidth()
            .padding(start = 8.dp, top = 4.dp, end = 8.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            maxLines = 1,
            style = TextStyle(
                color = WidgetPrimaryTextColor,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
            ),
            modifier = GlanceModifier.defaultWeight(),
        )
        Text(
            text = itemCount.toString(),
            style = TextStyle(
                color = WidgetAccentTextColor,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
            ),
        )
    }
}

@Composable
internal fun WidgetEmptyState(
    text: String,
    modifier: GlanceModifier,
) {
    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            maxLines = 2,
            style = TextStyle(
                color = WidgetSecondaryTextColor,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
            ),
            modifier = GlanceModifier.padding(16.dp),
        )
    }
}
