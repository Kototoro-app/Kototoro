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
	private val lock = Any()
	private val allResults = ArrayList<SourceTestResult>()
	private val pendingAvailability = LinkedHashMap<String, ContentSourceAvailability>()

	/** Tests [sources] with at most [SOURCE_TEST_MAX_PARALLELISM] running at once. Ignored while a run is active. */
	fun testAll(sources: List<ContentSource>) {
		if (job?.isActive == true) return
		val unique = sources.distinctBy { it.name }
		sourcesByKey.clear()
		unique.forEach { sourcesByKey[it.name] = it }
		synchronized(lock) {
			allResults.clear()
			pendingAvailability.clear()
		}
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
								onResult(source, result)
								_progress.update {
									it.copy(
										done = it.done + 1,
										running = it.running - source.name,
									)
								}
							}
						}
					}
				}
				completed = true
			} finally {
				val (finalResults, batch) = synchronized(lock) {
					val snapshot = allResults.toList()
					val pending = LinkedHashMap(pendingAvailability)
					pendingAvailability.clear()
					snapshot to pending
				}
				flushAvailability(batch)
				_progress.update {
					it.copy(running = emptySet(), results = finalResults, finished = true, cancelled = !completed)
				}
			}
		}
	}

	/**
	 * Collects a result. Results are published and verdicts persisted in batches of
	 * [RESULT_BATCH_SIZE] so a long run neither copies the whole list per result nor rewrites prefs per source.
	 */
	private fun onResult(source: ContentSource, result: SourceTestResult) {
		var published: List<SourceTestResult>? = null
		var batch: Map<String, ContentSourceAvailability>? = null
		synchronized(lock) {
			allResults += result
			pendingAvailability[source.name] = result.toAvailability()
			if (pendingAvailability.size >= RESULT_BATCH_SIZE) {
				published = allResults.toList()
				batch = LinkedHashMap(pendingAvailability)
				pendingAvailability.clear()
			}
		}
		batch?.let(::flushAvailability)
		published?.let { snapshot ->
			_progress.update { if (snapshot.size > it.results.size) it.copy(results = snapshot) else it }
		}
	}

	/** Persists the verdicts so the sources list shows the tested state of every source. */
	private fun flushAvailability(batch: Map<String, ContentSourceAvailability>) {
		if (batch.isEmpty()) return
		runCatching { sourceAvailabilityRepository.setAvailabilities(batch) }
	}

	private fun SourceTestResult.toAvailability(): ContentSourceAvailability = when (overall) {
		StepStatus.PASS, StepStatus.SKIP -> ContentSourceAvailability.AVAILABLE
		StepStatus.FAIL -> ContentSourceAvailability.EMPTY
		StepStatus.CLOUDFLARE -> ContentSourceAvailability.CLOUDFLARE
	}

	fun cancel() {
		job?.cancel()
	}

	/**
	 * JSON export of a finished run. Passing sources are only counted, never listed: the report
	 * is about the sources that do not work and the step where each one stopped.
	 */
	fun exportJson(): String {
		val results = _progress.value.results
		val problems = results
			.filter { it.overall == StepStatus.FAIL || it.overall == StepStatus.CLOUDFLARE }
			.sortedWith(compareBy({ it.overall != StepStatus.FAIL }, { it.title }))
		val sources = JSONArray()
		problems.forEach { result ->
			val steps = JSONObject()
			result.steps.forEach { (step, stepResult) ->
				val entry = JSONObject().put("status", stepResult.status.name.lowercase())
				if (stepResult.status != StepStatus.PASS && stepResult.message.isNotBlank()) {
					entry.put("message", stepResult.message)
				}
				steps.put(step.name.lowercase(), entry)
			}
			sources.put(
				JSONObject()
					.put("name", result.sourceKey)
					.put("title", result.title)
					.put("status", result.overall.name.lowercase())
					.put("durationMs", result.durationMs)
					.put("steps", steps),
			)
		}
		return JSONObject()
			.put("generated", Instant.now().toString())
			.put("total", results.size)
			.put("passed", results.count { it.overall == StepStatus.PASS })
			.put("failed", results.count { it.overall == StepStatus.FAIL })
			.put("cloudflare", results.count { it.overall == StepStatus.CLOUDFLARE })
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

	private companion object {
		const val RESULT_BATCH_SIZE = 20
	}
}
