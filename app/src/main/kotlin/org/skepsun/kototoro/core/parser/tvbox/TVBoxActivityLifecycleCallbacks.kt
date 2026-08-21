package org.skepsun.kototoro.core.parser.tvbox

import android.app.Activity
import android.os.Bundle
import com.github.tvbox.osc.util.AppManager
import org.skepsun.kototoro.core.ui.DefaultActivityLifecycleCallbacks
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TVBoxActivityLifecycleCallbacks @Inject constructor() : DefaultActivityLifecycleCallbacks {

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
        AppManager.getInstance().addActivity(activity)
    }

    override fun onActivityResumed(activity: Activity) {
        AppManager.getInstance().setCurrentActivity(activity)
    }

    override fun onActivityDestroyed(activity: Activity) {
        AppManager.getInstance().finishActivity(activity)
    }
}
