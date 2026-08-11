package org.skepsun.kototoro.core.ui.util

import android.app.Activity
import com.lagradost.cloudstream3.CommonActivity
import org.skepsun.kototoro.core.ui.DefaultActivityLifecycleCallbacks
import java.lang.ref.WeakReference
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 持有当前位于前台的 Activity，供需要真实窗口宿主的组件使用。
 */
@Singleton
class ForegroundActivityHolder @Inject constructor() : DefaultActivityLifecycleCallbacks {

    private var activityRef: WeakReference<Activity>? = null
    private var cloudstreamActivityRef: WeakReference<Activity>? = null

    val current: Activity?
        get() = activityRef?.get()

    override fun onActivityResumed(activity: Activity) {
        activityRef = WeakReference(activity)
        cloudstreamActivityRef = WeakReference(activity)
        CommonActivity.setActivityInstance(activity)
    }

    override fun onActivityPaused(activity: Activity) {
        if (activityRef?.get() == activity) {
            activityRef = null
        }
    }

    override fun onActivityDestroyed(activity: Activity) {
        if (activityRef?.get() == activity) {
            activityRef = null
        }
        if (cloudstreamActivityRef?.get() == activity) {
            cloudstreamActivityRef = null
            CommonActivity.setActivityInstance(null)
        }
    }
}
