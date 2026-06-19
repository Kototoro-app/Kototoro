package org.skepsun.kototoro.core.ui

import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.core.content.ContextCompat
import androidx.viewbinding.ViewBinding
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.ui.util.SystemUiController

abstract class BaseFullscreenActivity<B : ViewBinding> :
	BaseActivity<B>() {

	protected lateinit var systemUiController: SystemUiController

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		with(window) {
			systemUiController = SystemUiController(this)
			statusBarColor = Color.TRANSPARENT
			navigationBarColor = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O_MR1) {
				ContextCompat.getColor(this@BaseFullscreenActivity, R.color.dim)
			} else {
				Color.TRANSPARENT
			}
			if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
				attributes.layoutInDisplayCutoutMode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
					WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
				} else {
					WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
				}
			}
		}
		systemUiController.setSystemUiVisible(true)
	}
}
