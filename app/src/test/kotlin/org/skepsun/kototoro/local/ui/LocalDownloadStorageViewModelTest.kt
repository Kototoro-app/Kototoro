package org.skepsun.kototoro.local.ui

import androidx.lifecycle.ViewModelStore
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.local.data.LocalStorageManager
import org.skepsun.kototoro.local.data.index.LocalContentIndex

@OptIn(ExperimentalCoroutinesApi::class)
class LocalDownloadStorageViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private val store = ViewModelStore()
    private val changes = MutableSharedFlow<Unit>()
    private val storageManager = mockk<LocalStorageManager>()
    private val index = mockk<LocalContentIndex>()
    private var usedBytes = 100L

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        every { index.observeChanges() } returns changes
        coEvery { storageManager.computeStorageSize() } answers { usedBytes }
        coEvery { storageManager.computeAvailableSize() } returns 1000L
    }

    @AfterEach
    fun tearDown() {
        store.clear()
        Dispatchers.resetMain()
    }

    @Test
    fun `import refreshes storage while download queue stays idle`() = runTest(dispatcher) {
        val model = createModel()
        model.onDownloadQueueIdleChanged(true)
        runCurrent()
        model.stats.value?.usedBytes shouldBe 100L

        usedBytes = 200L
        changes.emit(Unit)
        runCurrent()

        model.stats.value?.usedBytes shouldBe 200L
        coVerify(exactly = 2) { storageManager.computeStorageSize() }
    }

    @Test
    fun `active downloads defer index scans until queue returns to idle`() = runTest(dispatcher) {
        val model = createModel()
        runCurrent()
        changes.emit(Unit)
        runCurrent()
        coVerify(exactly = 0) { storageManager.computeStorageSize() }

        model.onDownloadQueueIdleChanged(true)
        runCurrent()
        model.onDownloadQueueIdleChanged(false)
        runCurrent()
        usedBytes = 300L
        changes.emit(Unit)
        runCurrent()
        coVerify(exactly = 1) { storageManager.computeStorageSize() }

        model.onDownloadQueueIdleChanged(true)
        runCurrent()
        model.stats.value?.usedBytes shouldBe 300L
        coVerify(exactly = 2) { storageManager.computeStorageSize() }
    }

    @Test
    fun `index change during scan is not discarded`() = runTest(dispatcher) {
        val firstScan = CompletableDeferred<Long>()
        var scans = 0
        coEvery { storageManager.computeStorageSize() } coAnswers {
            if (++scans == 1) firstScan.await() else 400L
        }
        val model = createModel()
        model.onDownloadQueueIdleChanged(true)
        runCurrent()

        changes.emit(Unit)
        runCurrent()
        firstScan.complete(100L)
        runCurrent()

        model.stats.value?.usedBytes shouldBe 400L
        scans shouldBe 2
    }

    private fun createModel() = LocalDownloadStorageViewModel(storageManager, index).also {
        store.put("storage", it)
    }
}
