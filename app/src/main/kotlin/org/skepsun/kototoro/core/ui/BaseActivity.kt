package org.skepsun.kototoro.core.ui

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.annotation.CallSuper
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.appcompat.view.ActionMode
import androidx.appcompat.widget.Toolbar
import androidx.core.app.ActivityCompat
import androidx.core.view.OnApplyWindowInsetsListener
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.fragment.app.FragmentManager
import androidx.viewbinding.ViewBinding
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import org.skepsun.kototoro.BuildConfig
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.exceptions.resolve.ExceptionResolver
import org.skepsun.kototoro.core.nav.AppRouter
import org.skepsun.kototoro.core.prefs.ColorScheme
import org.skepsun.kototoro.core.prefs.InterfaceStyle
import org.skepsun.kototoro.core.ui.util.ActionModeDelegate
import org.skepsun.kototoro.core.ui.util.configureSafeAreaWindow
import org.skepsun.kototoro.core.util.ext.isWebViewUnavailable
import org.skepsun.kototoro.main.ui.protect.ScreenshotPolicyHelper
import androidx.appcompat.R as appcompatR

abstract class BaseActivity<B : ViewBinding> :
	AppCompatActivity(),
	OnApplyWindowInsetsListener,
	ScreenshotPolicyHelper.ContentContainer {

	private var isAmoledTheme = false

	lateinit var viewBinding: B
		private set

	lateinit var exceptionResolver: ExceptionResolver
		private set

	@JvmField
	val actionModeDelegate = ActionModeDelegate()

	private lateinit var entryPoint: BaseActivityEntryPoint

	val kototoroAppSettings: org.skepsun.kototoro.core.prefs.AppSettings
		get() = entryPoint.settings

	override fun attachBaseContext(newBase: Context) {
		entryPoint = EntryPointAccessors.fromApplication<BaseActivityEntryPoint>(newBase.applicationContext)
		if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
			AppCompatDelegate.setApplicationLocales(entryPoint.settings.appLocales)
		}
		super.attachBaseContext(newBase)
	}

	override fun onCreate(savedInstanceState: Bundle?) {
		val settings = entryPoint.settings
		isAmoledTheme = settings.isAmoledTheme
		setTheme(settings.colorScheme.styleResId)
		if (isAmoledTheme) {
			setTheme(R.style.ThemeOverlay_Kototoro_Amoled)
		}
		if (settings.interfaceStyle == InterfaceStyle.IOS && settings.colorScheme == ColorScheme.IOS) {
			setTheme(R.style.ThemeOverlay_Kototoro_IosPalette)
		}
		if (settings.interfaceStyle == InterfaceStyle.IOS) {
			setTheme(R.style.ThemeOverlay_Kototoro_ExpressiveComponents)
		}
		when(settings.loadingCircleStyle) {
			org.skepsun.kototoro.core.prefs.AppSettings.LoadingCircleStyle.THICK_STRAIGHT -> setTheme(R.style.ThemeOverlay_Kototoro_Loading_ThickStraight)
			org.skepsun.kototoro.core.prefs.AppSettings.LoadingCircleStyle.THICK_WAVY -> setTheme(R.style.ThemeOverlay_Kototoro_Loading_ThickWavy)
			org.skepsun.kototoro.core.prefs.AppSettings.LoadingCircleStyle.THIN_STRAIGHT -> setTheme(R.style.ThemeOverlay_Kototoro_Loading_ThinStraight)
			org.skepsun.kototoro.core.prefs.AppSettings.LoadingCircleStyle.THIN_WAVY -> setTheme(R.style.ThemeOverlay_Kototoro_Loading_ThinWavy)
		}
		when(settings.popupRadius) {
			12 -> setTheme(R.style.ThemeOverlay_Kototoro_PopupRadius_12)
			16 -> setTheme(R.style.ThemeOverlay_Kototoro_PopupRadius_16)
			20 -> setTheme(R.style.ThemeOverlay_Kototoro_PopupRadius_20)
			24 -> setTheme(R.style.ThemeOverlay_Kototoro_PopupRadius_24)
		}
		putDataToExtras(intent)
		exceptionResolver = entryPoint.exceptionResolverFactory.create(this)
		super.onCreate(savedInstanceState)
		enableEdgeToEdge()
		WindowCompat.setDecorFitsSystemWindows(window, false)
		configureSafeAreaWindow()
	}

	override fun onPostCreate(savedInstanceState: Bundle?) {
		super.onPostCreate(savedInstanceState)
		onBackPressedDispatcher.addCallback(actionModeDelegate)
	}

	override fun onNewIntent(intent: Intent) {
		putDataToExtras(intent)
		super.onNewIntent(intent)
	}

	@Deprecated("Use ViewBinding", level = DeprecationLevel.ERROR)
	override fun setContentView(layoutResID: Int) = throw UnsupportedOperationException()

	@Deprecated("Use ViewBinding", level = DeprecationLevel.ERROR)
	override fun setContentView(view: View?) = throw UnsupportedOperationException()

	protected fun setContentView(binding: B) {
		this.viewBinding = binding
		super.setContentView(binding.root)
		if (this !is org.skepsun.kototoro.main.ui.MainActivity) {
			ViewCompat.setOnApplyWindowInsetsListener(binding.root, this)
		}
		val toolbar = (binding.root.findViewById<View>(R.id.toolbar) as? Toolbar)
		toolbar?.let(this::setSupportActionBar)
	}

	protected fun setDisplayHomeAsUp(isEnabled: Boolean, showUpAsClose: Boolean) {
		supportActionBar?.run {
			setDisplayHomeAsUpEnabled(isEnabled)
			if (showUpAsClose) {
				setHomeAsUpIndicator(appcompatR.drawable.abc_ic_clear_material)
			}
		}
	}

	override fun onSupportNavigateUp(): Boolean {
		val fm = supportFragmentManager
		if (fm.isStateSaved) {
			return false
		}
		if (fm.backStackEntryCount > 0) {
			fm.popBackStack()
		} else {
			dispatchNavigateUp()
		}
		return true
	}

	override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
		return super.onKeyDown(keyCode, event)
	}

	protected fun isDarkAmoledTheme(): Boolean {
		val uiMode = resources.configuration.uiMode
		val isNight = uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
		return isNight && isAmoledTheme
	}

	@CallSuper
	override fun onSupportActionModeStarted(mode: ActionMode) {
		super.onSupportActionModeStarted(mode)
		actionModeDelegate.onSupportActionModeStarted(mode, window)
	}

	@CallSuper
	override fun onSupportActionModeFinished(mode: ActionMode) {
		super.onSupportActionModeFinished(mode)
		actionModeDelegate.onSupportActionModeFinished(mode, window)
	}

	protected open fun dispatchNavigateUp() {
		val upIntent = parentActivityIntent
		if (upIntent != null) {
			if (!navigateUpTo(upIntent)) {
				startActivity(upIntent)
			}
		} else {
			finishAfterTransition()
		}
	}

	override fun isNsfwContent(): Flow<Boolean> = flowOf(false)

	private fun putDataToExtras(intent: Intent?) {
		intent?.putExtra(AppRouter.KEY_DATA, intent.data)
	}

	protected fun setContentViewWebViewSafe(viewBindingProducer: () -> B): Boolean {
		return try {
			setContentView(viewBindingProducer())
			true
		} catch (e: Exception) {
			if (e.isWebViewUnavailable()) {
				Toast.makeText(this, R.string.web_view_unavailable, Toast.LENGTH_LONG).show()
				finishAfterTransition()
				false
			} else {
				throw e
			}
		}
	}

	protected fun hasViewBinding() = ::viewBinding.isInitialized
}
