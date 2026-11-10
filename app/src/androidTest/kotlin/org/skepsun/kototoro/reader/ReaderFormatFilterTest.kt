package org.skepsun.kototoro.reader

import android.content.Intent
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.dev.IdleProbeActivity
import org.skepsun.kototoro.main.ui.compose.ReaderFormatFilter
import org.skepsun.kototoro.parsers.model.ContentType

@RunWith(AndroidJUnit4::class)
class ReaderFormatFilterTest {
    @Test
    fun restoredVideoSelectionOffersOnlyReadingFormatsAtLargeFontScale() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val selection = AtomicReference<ContentType?>(ContentType.VIDEO)
        val intent = Intent(context, IdleProbeActivity::class.java).putExtra("scene_recovery", true)
        ActivityScenario.launch<IdleProbeActivity>(intent).use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent {
                    var selected by remember { mutableStateOf<ContentType?>(ContentType.VIDEO) }
                    val density = LocalDensity.current.density
                    CompositionLocalProvider(LocalDensity provides Density(density, fontScale = 2f)) {
                        MaterialTheme {
                            ReaderFormatFilter(
                                selectedType = selected,
                                enabledTypes = setOf(ContentType.MANGA, ContentType.NOVEL),
                                onTypeSelected = { selected = it; selection.set(it) },
                            )
                        }
                    }
                }
            }
            val device = UiDevice.getInstance(instrumentation)
            val description = context.getString(R.string.content_type_filter) + ": " + context.getString(R.string.all)
            checkNotNull(device.wait(Until.findObject(By.desc(description)), 5_000)).click()
            val novel = checkNotNull(device.wait(Until.findObject(By.text(context.getString(R.string.content_type_novel))), 5_000))
            assertFalse(device.hasObject(By.text(context.getString(R.string.content_type_video))))
            novel.click()
            instrumentation.waitForIdleSync()
            assertEquals(ContentType.NOVEL, selection.get())
        }
    }
}
