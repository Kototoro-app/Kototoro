package org.skepsun.kototoro.core.ui

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.core.view.WindowCompat
import androidx.appcompat.app.AppCompatActivity
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.exceptions.resolve.ExceptionResolver
import org.skepsun.kototoro.core.prefs.AppSettings
import org.skepsun.kototoro.core.prefs.ColorScheme
import org.skepsun.kototoro.core.prefs.InterfaceStyle
import org.skepsun.kototoro.core.prefs.observeAsState
import org.skepsun.kototoro.core.ui.theme.KototoroTheme
import org.skepsun.kototoro.core.ui.util.configureSafeAreaWindow
import org.skepsun.kototoro.main.ui.protect.ScreenshotPolicyHelper

abstract class BaseComposeActivity :
    AppCompatActivity(),
    ScreenshotPolicyHelper.ContentContainer {

    data class ComposeModal(
        val key: String,
        val content: @Composable () -> Unit,
    )

    private var isAmoledTheme = false
    private lateinit var entryPoint: BaseActivityEntryPoint

    lateinit var exceptionResolver: ExceptionResolver
        private set

    private val modalStack = emptyList<ComposeModal>().toMutableStateList()
    private var nextModalId = 0

    val snackbarHostState = SnackbarHostState()

    val kototoroAppSettings: AppSettings
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
        applyConfiguredTheme(settings)
        putDataToExtras(intent)
        exceptionResolver = entryPoint.exceptionResolverFactory.create(this)
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        WindowCompat.setDecorFitsSystemWindows(window, false)
        configureSafeAreaWindow()
    }

    override fun onNewIntent(intent: Intent) {
        putDataToExtras(intent)
        super.onNewIntent(intent)
    }

    protected fun setComposeContent(content: @Composable () -> Unit) {
        setContent {
            val cornerRadius by kototoroAppSettings.observeAsState(AppSettings.KEY_POPUP_RADIUS) {
                cornerRadius
            }
            KototoroTheme(cornerRadius = cornerRadius) {
                Box(modifier = Modifier.fillMaxSize()) {
                    content()
                    SnackbarHost(
                        hostState = snackbarHostState,
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .navigationBarsPadding(),
                    )
                    modalStack.forEach { modal ->
                        key(modal.key) {
                            modal.content()
                        }
                    }
                }
            }
        }
    }

    fun showComposeModal(key: String = "compose-modal-${nextModalId++}", content: @Composable () -> Unit) {
        modalStack.add(ComposeModal(key, content))
    }

    fun dismissComposeModal() {
        if (modalStack.isNotEmpty()) {
            modalStack.removeAt(modalStack.lastIndex)
        }
    }

    fun dismissComposeModal(key: String) {
        val index = modalStack.indexOfLast { it.key == key }
        if (index >= 0) {
            modalStack.removeAt(index)
        }
    }

    fun dismissAllComposeModals() {
        modalStack.clear()
    }

    override fun isNsfwContent(): Flow<Boolean> = flowOf(false)

    protected fun isDarkAmoledTheme(): Boolean {
        val uiMode = resources.configuration.uiMode
        val isNight = uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        return isNight && isAmoledTheme
    }

    private fun applyConfiguredTheme(settings: AppSettings) {
        setTheme(settings.colorScheme.styleResId)
        if (isAmoledTheme) {
            setTheme(R.style.ThemeOverlay_Kototoro_Amoled)
        }
        if (settings.interfaceStyle == InterfaceStyle.IOS && settings.colorScheme == ColorScheme.IOS) {
            setTheme(R.style.ThemeOverlay_Kototoro_IosPalette)
        }
        if (settings.interfaceStyle == InterfaceStyle.MATERIAL_3_EXPRESSIVE) {
            setTheme(R.style.ThemeOverlay_Kototoro_ExpressiveComponents)
        }
        when (settings.loadingCircleStyle) {
            AppSettings.LoadingCircleStyle.THICK_STRAIGHT -> setTheme(R.style.ThemeOverlay_Kototoro_Loading_ThickStraight)
            AppSettings.LoadingCircleStyle.THICK_WAVY -> setTheme(R.style.ThemeOverlay_Kototoro_Loading_ThickWavy)
            AppSettings.LoadingCircleStyle.THIN_STRAIGHT -> setTheme(R.style.ThemeOverlay_Kototoro_Loading_ThinStraight)
            AppSettings.LoadingCircleStyle.THIN_WAVY -> setTheme(R.style.ThemeOverlay_Kototoro_Loading_ThinWavy)
        }
        when (settings.popupRadius) {
            12 -> setTheme(R.style.ThemeOverlay_Kototoro_PopupRadius_12)
            16 -> setTheme(R.style.ThemeOverlay_Kototoro_PopupRadius_16)
            20 -> setTheme(R.style.ThemeOverlay_Kototoro_PopupRadius_20)
            24 -> setTheme(R.style.ThemeOverlay_Kototoro_PopupRadius_24)
        }
    }

    private fun putDataToExtras(intent: Intent?) {
        intent?.putExtra(org.skepsun.kototoro.core.nav.AppRouter.KEY_DATA, intent.data)
    }
}
