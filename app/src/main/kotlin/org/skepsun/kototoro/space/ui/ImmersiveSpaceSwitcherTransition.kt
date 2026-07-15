package org.skepsun.kototoro.space.ui

import android.content.Intent
import android.graphics.PointF

object ImmersiveSpaceSwitcherTransition {
	private const val ExtraOriginCenterX = "org.skepsun.kototoro.extra.SPACE_SWITCHER_ORIGIN_CENTER_X"
	private const val ExtraOriginCenterY = "org.skepsun.kototoro.extra.SPACE_SWITCHER_ORIGIN_CENTER_Y"

	@Volatile
	private var detailsOrigin: PointF? = null

	fun updateDetailsOrigin(centerX: Float, centerY: Float) {
		detailsOrigin = PointF(centerX, centerY)
	}

	fun clearDetailsOrigin() {
		detailsOrigin = null
	}

	fun attachDetailsOrigin(intent: Intent): Intent = intent.apply {
		val origin = detailsOrigin ?: return@apply
		putExtra(ExtraOriginCenterX, origin.x)
		putExtra(ExtraOriginCenterY, origin.y)
	}

	fun consumeOrigin(intent: Intent): PointF? {
		if (!intent.hasExtra(ExtraOriginCenterX) || !intent.hasExtra(ExtraOriginCenterY)) return null
		val origin = PointF(
			intent.getFloatExtra(ExtraOriginCenterX, 0f),
			intent.getFloatExtra(ExtraOriginCenterY, 0f),
		)
		intent.removeExtra(ExtraOriginCenterX)
		intent.removeExtra(ExtraOriginCenterY)
		return origin
	}
}
