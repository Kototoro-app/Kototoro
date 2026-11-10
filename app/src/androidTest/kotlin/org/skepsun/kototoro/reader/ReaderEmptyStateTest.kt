package org.skepsun.kototoro.reader

import android.content.Intent
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.skepsun.kototoro.core.dev.IdleProbeActivity
import org.skepsun.kototoro.list.ui.compose.ContentCollectionEmpty

@RunWith(AndroidJUnit4::class)
class ReaderEmptyStateTest {
    @Test
    fun recoveryActionRemainsReachableWithLargeFonts() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val clicked = AtomicBoolean(false)
        val heightDp = AtomicReference(0f)
        val laidOut = CountDownLatch(1)
        val intent = Intent(instrumentation.targetContext, IdleProbeActivity::class.java)
            .putExtra("scene_recovery", true)
        ActivityScenario.launch<IdleProbeActivity>(intent).use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent {
                    val density = LocalDensity.current.density
                    CompositionLocalProvider(LocalDensity provides Density(density, fontScale = 2f)) {
                        MaterialTheme {
                            ContentCollectionEmpty(
                                title = "Votre bibliothèque vous attend",
                                subtitle = "Ajoutez un manga ou un roman pour commencer à lire.",
                                actionLabel = "Découvrir les sources",
                                onAction = { clicked.set(true) },
                                actionModifier = Modifier.onGloballyPositioned {
                                    heightDp.set(it.size.height / density)
                                    laidOut.countDown()
                                },
                            )
                        }
                    }
                }
            }
            assertTrue("The recovery button must finish layout", laidOut.await(10, TimeUnit.SECONDS))
            assertTrue("The touch target must be at least 48dp", heightDp.get() >= 48f)
            val device = UiDevice.getInstance(instrumentation)
            val button = device.wait(Until.findObject(By.text("Découvrir les sources")), 5_000)
            checkNotNull(button) { "The recovery action is absent from the accessibility tree" }.click()
            instrumentation.waitForIdleSync()
            assertTrue("The recovery action must be usable", clicked.get())
        }
    }
}
