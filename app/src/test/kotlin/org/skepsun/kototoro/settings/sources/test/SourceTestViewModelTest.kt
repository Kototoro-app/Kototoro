package org.skepsun.kototoro.settings.sources.test

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.explore.data.ContentSourcesRepository
import org.skepsun.kototoro.explore.data.SourceAvailabilityRepository
import org.skepsun.kototoro.parsers.model.ContentSource
import org.skepsun.kototoro.parsers.model.ContentType

@OptIn(ExperimentalCoroutinesApi::class)
class SourceTestViewModelTest {

	private data class FakeSource(override val name: String) : ContentSource {
		override val locale: String = "en"
		override val contentType: ContentType = ContentType.MANGA
	}

	private val tester = mockk<SourceTester>()
	private val sourcesRepository = mockk<ContentSourcesRepository>(relaxed = true)
	private val availabilityRepository = mockk<SourceAvailabilityRepository>(relaxed = true)
	private val gates = HashMap<String, CompletableDeferred<StepStatus>>()
	private val testedNames = java.util.Collections.synchronizedList(mutableListOf<String>())

	@BeforeEach
	fun setUp() {
		Dispatchers.setMain(UnconfinedTestDispatcher())
		coEvery { tester.test(any(), any()) } coAnswers {
			val name = firstArg<ContentSource>().name
			testedNames += name
			val status = gates.getValue(name).await()
			resultOf(name, status)
		}
	}

	@AfterEach
	fun tearDown() {
		Dispatchers.resetMain()
	}

	private fun resultOf(name: String, status: StepStatus) = SourceTestResult(
		sourceKey = name,
		title = name,
		steps = mapOf(SourceTestStep.LIST to StepResult(status)),
	)

	private fun viewModel() = SourceTestViewModel(tester, sourcesRepository, availabilityRepository)

	private fun gate(vararg names: String) = names.forEach { gates[it] = CompletableDeferred() }

	private suspend fun SourceTestViewModel.await(predicate: (TestProgress) -> Boolean): TestProgress =
		withTimeout(10_000) { progress.first(predicate) }

	@Test
	fun `first result is published while the run is still going`() = runBlocking {
		gate("a", "b")
		val vm = viewModel()
		vm.testAll(listOf(FakeSource("a"), FakeSource("b")))
		gates.getValue("a").complete(StepStatus.PASS)

		val live = vm.await { it.results.size == 1 }
		assertFalse(live.finished)
		assertEquals(listOf("a"), live.results.map { it.sourceKey })

		gates.getValue("b").complete(StepStatus.PASS)
		val done = vm.await { it.finished }
		assertEquals(2, done.results.size)
		assertFalse(done.cancelled)
	}

	@Test
	fun `stop finishes as cancelled and keeps the collected results`() = runBlocking {
		gate("a")
		val vm = viewModel()
		coEvery { tester.test(match { it.name == "b" }, any()) } coAnswers {
			testedNames += "b"
			awaitCancellation()
		}
		vm.testAll(listOf(FakeSource("a"), FakeSource("b")))
		gates.getValue("a").complete(StepStatus.PASS)
		vm.await { it.results.size == 1 && "b" in it.running }

		vm.cancel()
		val stopped = vm.await { it.finished }
		assertTrue(stopped.cancelled)
		assertEquals(listOf("a"), stopped.results.map { it.sourceKey })
		assertEquals(1, stopped.done)
		assertEquals(2, stopped.total)
	}

	@Test
	fun `resume tests only the remaining sources and keeps done consistent with total`() = runBlocking {
		gate("a")
		val vm = viewModel()
		coEvery { tester.test(match { it.name == "b" }, any()) } coAnswers {
			testedNames += "b"
			awaitCancellation()
		}
		vm.testAll(listOf(FakeSource("a"), FakeSource("b")))
		gates.getValue("a").complete(StepStatus.PASS)
		vm.await { it.results.size == 1 && "b" in it.running }
		vm.cancel()
		vm.await { it.finished && it.cancelled }

		coEvery { tester.test(match { it.name == "b" }, any()) } coAnswers {
			testedNames += "b"
			resultOf("b", StepStatus.PASS)
		}
		vm.resume()

		val done = vm.await { it.finished && !it.cancelled }
		assertEquals(listOf("a", "b"), done.results.map { it.sourceKey }.sorted())
		assertEquals(2, done.done)
		assertEquals(2, done.total)
		assertEquals(1, testedNames.count { it == "a" })
		assertEquals(2, testedNames.count { it == "b" })
	}

	@Test
	fun `disableFailed skips locked sources and does not report them as disabled`() = runBlocking {
		gate("free", "locked", "ok")
		every { sourcesRepository.lockState("locked") } returns true
		every { sourcesRepository.lockState("free") } returns null
		val vm = viewModel()
		vm.testAll(listOf(FakeSource("free"), FakeSource("locked"), FakeSource("ok")))
		gates.getValue("free").complete(StepStatus.FAIL)
		gates.getValue("locked").complete(StepStatus.FAIL)
		gates.getValue("ok").complete(StepStatus.PASS)
		vm.await { it.finished }

		val targets = slot<Collection<ContentSource>>()
		coEvery { sourcesRepository.setSourcesEnabled(capture(targets), false) } returns mockk(relaxed = true)
		vm.disableFailed()

		val after = vm.await { it.disabledKeys.isNotEmpty() }
		assertEquals(setOf("free"), after.disabledKeys)
		assertEquals(listOf("free"), targets.captured.map { it.name })
		coVerify(exactly = 1) { sourcesRepository.setSourcesEnabled(any(), any()) }
	}
}
