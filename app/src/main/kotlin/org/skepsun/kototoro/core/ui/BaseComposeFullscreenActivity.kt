package org.skepsun.kototoro.core.ui

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.exceptions.resolve.ExceptionResolver
import org.skepsun.kototoro.core.nav.AppRouter
import org.skepsun.kototoro.core.ui.util.SystemUiController
import org.skepsun.kototoro.main.ui.protect.ScreenshotPolicyHelper

abstract class BaseComposeFullscreenActivity :
	AppCompatActivity(),
	ScreenshotPolicyHelper.ContentContainer {

	protected lateinit var systemUiController: SystemUiController
		private set

	protected lateinit var exceptionResolver: ExceptionResolver
		private set

	private lateinit var entryPoint: BaseActivityEntryPoint

	override fun attachBaseContext(newBase: Context) {
		entryPoint = EntryPointAccessors.fromApplication<BaseActivityEntryPoint>(newBase.applicationContext)
		if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
			AppCompatDelegate.setApplicationLocales(entryPoint.settings.appLocales)
		}
		super.attachBaseContext(newBase)
	}

	override fun onCreate(savedInstanceState: Bundle?) {
		applyKototoroActivityTheme(entryPoint.settings)
		putDataToExtras(intent)
		exceptionResolver = entryPoint.exceptionResolverFactory.create(this)
		super.onCreate(savedInstanceState)
		configureKototoroEdgeToEdge()
		with(window) {
			systemUiController = SystemUiController(this)
			statusBarColor = Color.TRANSPARENT
			navigationBarColor = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O_MR1) {
				ContextCompat.getColor(this@BaseComposeFullscreenActivity, R.color.dim)
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

	override fun onNewIntent(intent: Intent) {
		putDataToExtras(intent)
		super.onNewIntent(intent)
	}

	override fun isNsfwContent(): Flow<Boolean> = flowOf(false)

	private fun putDataToExtras(intent: Intent?) {
		intent?.putExtra(AppRouter.KEY_DATA, intent.data)
	}
}
