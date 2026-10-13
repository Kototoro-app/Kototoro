package org.skepsun.kototoro.settings.sources.test

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.json.JSONArray
import org.json.JSONObject
import org.skepsun.kototoro.core.model.ContentSourceAvailability
import org.skepsun.kototoro.explore.data.SourceAvailabilityRepository
import org.skepsun.kototoro.explore.data.ContentSourcesRepository
import org.skepsun.kototoro.parsers.model.ContentSource
import org.skepsun.kototoro.parsers.util.runCatchingCancellable
import java.time.Instant
import javax.inject.Inject

@HiltViewModel
class SourceTestViewModel @Inject constructor(
	private val tester: SourceTester,
	private val contentSourcesRepository: ContentSourcesRepository,
	private val sourceAvailabilityRepository: SourceAvailabilityRepository,
) : ViewModel() {

	private val _progress = MutableStateFlow(TestProgress())
	val progress: StateFlow<TestProgress> = _progress.asStateFlow()

	private val sourcesByKey = mutableMapOf<String, ContentSource>()
	private var job: Job? = null

	/** Tests [sources] with at most [SOURCE_TEST_MAX_PARALLELISM] running at once. Ignored while a run is active. */
	fun testAll(sources: List<ContentSource>) {
		if (job?.isActive == true) return
		val unique = sources.distinctBy { it.name }
		sourcesByKey.clear()
		unique.forEach { sourcesByKey[it.name] = it }
		_progress.value = TestProgress(total = unique.size)
		job = viewModelScope.launch(Dispatchers.IO) {
			var completed = false
			try {
				val semaphore = Semaphore(SOURCE_TEST_MAX_PARALLELISM)
				coroutineScope {
					unique.forEach { source ->
						launch {
							semaphore.withPermit {
								_progress.update { it.copy(running = it.running + source.name) }
								val result = tester.test(source)
								recordAvailability(source, result)
								_progress.update {
									it.copy(
										done = it.done + 1,
										running = it.running - source.name,
										results = it.results + result,
									)
								}
							}
						}
					}
				}
				completed = true
			} finally {
				_progress.update { it.copy(running = emptySet(), finished = true, cancelled = !completed) }
			}
		}
	}

	/** Persists the verdict so the sources list shows the tested state of every source. */
	private fun recordAvailability(source: ContentSource, result: SourceTestResult) {
		val availability = when (result.overall) {
			StepStatus.PASS, StepStatus.SKIP -> ContentSourceAvailability.AVAILABLE
			StepStatus.FAIL -> ContentSourceAvailability.EMPTY
			StepStatus.CLOUDFLARE -> ContentSourceAvailability.CLOUDFLARE
		}
		runCatching { sourceAvailabilityRepository.setAvailability(source, availability) }
	}

	fun cancel() {
		job?.cancel()
	}

	fun exportJson(): String {
		val sources = JSONArray()
		_progress.value.results.forEach { result ->
			val steps = JSONObject()
			result.steps.forEach { (step, stepResult) ->
				steps.put(step.name, stepResult.status.name.lowercase())
			}
			sources.put(
				JSONObject()
					.put("name", result.sourceKey)
					.put("status", result.overall.name.lowercase())
					.put("steps", steps),
			)
		}
		return JSONObject()
			.put("generated", Instant.now().toString())
			.put("sources", sources)
			.toString()
	}

	/** Disables every source of the current run whose overall verdict is FAIL. */
	fun disableFailed() {
		val current = _progress.value
		val targets = current.results
			.filter { it.overall == StepStatus.FAIL && it.sourceKey !in current.disabledKeys }
			.mapNotNull { sourcesByKey[it.sourceKey] }
		if (targets.isEmpty()) return
		val keys = targets.mapTo(mutableSetOf()) { it.name }
		viewModelScope.launch(Dispatchers.IO) {
			runCatchingCancellable {
				contentSourcesRepository.setSourcesEnabled(targets, false)
			}.onSuccess {
				_progress.update { it.copy(disabledKeys = it.disabledKeys + keys, errorMessage = null) }
			}.onFailure { e ->
				_progress.update { it.copy(errorMessage = e.message ?: e.javaClass.simpleName) }
			}
		}
	}
}
