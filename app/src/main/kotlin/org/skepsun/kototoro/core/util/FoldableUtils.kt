package org.skepsun.kototoro.core.util

import android.app.Activity
import android.content.Context
import android.content.res.Configuration
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.prefs.AppSettings
import org.skepsun.kototoro.core.prefs.TabletUiMode
import androidx.window.layout.FoldingFeature
import androidx.window.layout.WindowInfoTracker
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

/**
 * 折叠屏设备工具类，用于检测折叠屏的展开状态
 */
object FoldableUtils {

    /**
     * 观察窗口布局信息，检测折叠屏展开状态
     * @param activity 当前Activity
     * @param lifecycleOwner 生命周期所有者
     * @return 折叠屏展开状态的StateFlow
     */
    fun observeFoldableState(
        activity: Activity,
        lifecycleOwner: LifecycleOwner
    ): StateFlow<Boolean> {
        val foldableState = MutableStateFlow(false)
        
        WindowInfoTracker.getOrCreate(activity)
            .windowLayoutInfo(activity)
            .onEach { info ->
                val fold = info.displayFeatures.filterIsInstance<FoldingFeature>().firstOrNull()
                val unfolded = when (fold?.state) {
                    FoldingFeature.State.HALF_OPENED, FoldingFeature.State.FLAT -> true
                    else -> false
                }
                foldableState.value = unfolded
            }
            .launchIn(lifecycleOwner.lifecycleScope)
        
        return foldableState
    }

    /**
     * 判断当前是否应该使用横屏布局
     * 包括传统横屏和折叠屏展开状态
     * @param activity 当前Activity
     * @param isFoldUnfolded 折叠屏展开状态
     * @return 是否应该使用横屏布局
     */
    fun shouldUseLandscapeLayout(activity: Activity, isFoldUnfolded: Boolean): Boolean {
        val isLandscape = activity.resources.configuration.orientation == 
            android.content.res.Configuration.ORIENTATION_LANDSCAPE
        return isLandscape || isFoldUnfolded
    }

    /**
     * 判断当前窗口是否满足双栏布局的最小宽度
     */
    fun shouldUseTwoPaneLayout(activity: Activity, minWidthDp: Int = 600): Boolean {
        val config = activity.resources.configuration
        val minWindowDp = minOf(config.screenWidthDp, config.screenHeightDp)
        return minWindowDp >= minWidthDp
    }

    fun shouldUseTabletLayout(
        context: Context,
        settings: AppSettings,
        configuration: Configuration = context.resources.configuration,
        minWidthDp: Int = 600,
    ): Boolean {
        return when (settings.tabletUiMode) {
            TabletUiMode.DISABLED -> false
            TabletUiMode.STRICT -> context.resources.getBoolean(R.bool.is_tablet)
            TabletUiMode.RELAXED -> context.resources.getBoolean(R.bool.is_tablet) ||
                configuration.screenWidthDp >= minWidthDp
        }
    }
}
