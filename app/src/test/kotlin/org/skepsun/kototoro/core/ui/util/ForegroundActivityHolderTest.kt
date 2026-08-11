package org.skepsun.kototoro.core.ui.util

import android.app.Activity
import com.lagradost.cloudstream3.CommonActivity
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.mockk.mockk

class ForegroundActivityHolderTest : FunSpec({

    lateinit var holder: ForegroundActivityHolder

    beforeTest {
        CommonActivity.setActivityInstance(null)
        holder = ForegroundActivityHolder()
    }

    afterTest {
        CommonActivity.setActivityInstance(null)
    }

    test("paused activity remains available to Cloudstream plugins") {
        val activity = mockk<Activity>(relaxed = true)

        holder.onActivityResumed(activity)
        holder.onActivityPaused(activity)

        holder.current.shouldBeNull()
        CommonActivity.activity shouldBe activity
    }

    test("destroyed activity is removed from Cloudstream bridge") {
        val activity = mockk<Activity>(relaxed = true)

        holder.onActivityResumed(activity)
        holder.onActivityPaused(activity)
        holder.onActivityDestroyed(activity)

        holder.current.shouldBeNull()
        CommonActivity.activity.shouldBeNull()
    }

    test("destroying an old activity does not clear the newer activity") {
        val oldActivity = mockk<Activity>(relaxed = true)
        val newActivity = mockk<Activity>(relaxed = true)

        holder.onActivityResumed(oldActivity)
        holder.onActivityPaused(oldActivity)
        holder.onActivityResumed(newActivity)
        holder.onActivityDestroyed(oldActivity)

        holder.current shouldBe newActivity
        CommonActivity.activity shouldBe newActivity
    }
})
