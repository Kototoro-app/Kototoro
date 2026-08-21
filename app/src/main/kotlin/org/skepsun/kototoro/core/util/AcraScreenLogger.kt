package org.skepsun.kototoro.core.util

import android.app.Activity
import android.content.Context
import android.os.Bundle
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.FragmentManager
import androidx.fragment.app.FragmentManager.FragmentLifecycleCallbacks
import org.acra.ACRA
import org.skepsun.kototoro.core.ui.DefaultActivityLifecycleCallbacks
import java.time.LocalTime
import java.time.temporal.ChronoUnit
import java.util.WeakHashMap
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AcraScreenLogger @Inject constructor() : FragmentLifecycleCallbacks(), DefaultActivityLifecycleCallbacks {

    private val keys = WeakHashMap<Any, String>()

    override fun onFragmentAttached(fm: FragmentManager, f: Fragment, context: Context) {
        super.onFragmentAttached(fm, f, context)
        ACRA.errorReporter.putCustomData(f.key(), SCREEN_STATE_ATTACHED)
    }

    override fun onFragmentDetached(fm: FragmentManager, f: Fragment) {
        super.onFragmentDetached(fm, f)
        ACRA.errorReporter.removeCustomData(f.key())
        keys.remove(f)
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
        super.onActivityCreated(activity, savedInstanceState)
        ACRA.errorReporter.putCustomData(activity.key(), SCREEN_STATE_CREATED)
        (activity as? FragmentActivity)?.supportFragmentManager?.registerFragmentLifecycleCallbacks(this, true)
    }

    override fun onActivityDestroyed(activity: Activity) {
        super.onActivityDestroyed(activity)
        ACRA.errorReporter.removeCustomData(activity.key())
        keys.remove(activity)
    }

    private fun Any.key() = keys.getOrPut(this) {
        val time = LocalTime.now().truncatedTo(ChronoUnit.SECONDS)
        "$time: ${javaClass.simpleName}"
    }

    private companion object {
        const val SCREEN_STATE_ATTACHED = "attached"
        const val SCREEN_STATE_CREATED = "created"
    }
}
