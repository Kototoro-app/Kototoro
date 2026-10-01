package org.skepsun.kototoro.settings.sources

import android.content.Context
import android.content.pm.PackageManager
import androidx.arch.core.executor.ArchTaskExecutor
import androidx.arch.core.executor.TaskExecutor
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import java.io.File
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.emptyFlow
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.skepsun.kototoro.core.prefs.AppSettings
import org.skepsun.kototoro.explore.data.ContentSourcesRepository

class SourcesSettingsViewModelTest {

    @TempDir
    lateinit var filesDir: File

    private val failures = mutableListOf<Throwable>()
    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.Unconfined + CoroutineExceptionHandler { _, error -> failures.add(error) },
    )

    @BeforeEach
    fun setUp() {
        // A fast IO task may complete before the constructor advances to its next initializer.
        mockkStatic(Dispatchers::class)
        every { Dispatchers.IO } returns Dispatchers.Unconfined
        mockkStatic("androidx.lifecycle.ViewModelKt")
        every { any<ViewModel>().viewModelScope } returns scope
        ArchTaskExecutor.getInstance().setDelegate(object : TaskExecutor() {
            override fun executeOnDiskIO(runnable: Runnable) = runnable.run()
            override fun postToMainThread(runnable: Runnable) = runnable.run()
            override fun isMainThread() = true
        })
    }

    @AfterEach
    fun tearDown() {
        scope.cancel()
        ArchTaskExecutor.getInstance().setDelegate(null)
        unmockkStatic("androidx.lifecycle.ViewModelKt")
        unmockkStatic(Dispatchers::class)
    }

    @Test
    fun `fast initial plugin scan publishes an empty list without a null state crash`() {
        val model = createModel()

        assertEquals(emptyList<Throwable>(), failures)
        assertEquals(emptyList<File>(), model.installedPlugins.value)
    }

    @Test
    fun `initial scan and reload publish only jar files in name order`() {
        val plugins = File(filesDir, "plugins").apply { mkdirs() }
        File(plugins, "zeta.jar").writeText("")
        File(plugins, "Alpha.JAR").writeText("")
        File(plugins, "readme.txt").writeText("")
        val model = createModel()

        assertEquals(emptyList<Throwable>(), failures)
        assertEquals(listOf("Alpha.JAR", "zeta.jar"), model.installedPlugins.value?.map { it.name })

        File(plugins, "beta.jar").writeText("")
        model.loadPlugins()

        assertEquals(emptyList<Throwable>(), failures)
        assertEquals(listOf("Alpha.JAR", "beta.jar", "zeta.jar"), model.installedPlugins.value?.map { it.name })
    }

    private fun createModel(): SourcesSettingsViewModel {
        val repository = mockk<ContentSourcesRepository> {
            every { observeEnabledSourcesCount() } returns emptyFlow()
            every { observeAvailableSourcesCount() } returns emptyFlow()
            every { observeBuiltInSourcesCount() } returns emptyFlow()
            every { observeJsonSourcesCount() } returns emptyFlow()
            every { observeMihonSourcesCount() } returns emptyFlow()
            every { observeAniyomiSourcesCount() } returns emptyFlow()
            every { observeIReaderSourcesCount() } returns emptyFlow()
        }
        val manager = mockk<PackageManager> {
            every { getComponentEnabledSetting(any()) } returns PackageManager.COMPONENT_ENABLED_STATE_DEFAULT
        }
        val context = mockk<Context>(relaxed = true)
        every { context.filesDir } returns filesDir
        every { context.packageManager } returns manager
        return SourcesSettingsViewModel(repository, context, mockk<AppSettings>(relaxed = true))
    }
}
