package org.skepsun.kototoro.settings.sources.test

import android.os.SystemClock
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
	private var lastPublishAt = -1L
	private var lastFlushAt = SystemClock.elapsedRealtime()

	/** Tests [sources] with at most [SOURCE_TEST_MAX_PARALLELISM] running at once. Ignored while a run is active. */
	fun testAll(sources: List<ContentSource>) {
		if (job?.isActive == true) return
		val unique = sources.distinctBy { it.name }
		sourcesByKey.clear()
		unique.forEach { sourcesByKey[it.name] = it }
		synchronized(lock) {
			allResults.clear()
			pendingAvailability.clear()
			lastPublishAt = -1L
			lastFlushAt = SystemClock.elapsedRealtime()
		}
		_progress.value = TestProgress(total = unique.size)
		runSources(unique)
	}

	/** Continues a stopped run: keeps finished results and tests only the sources that have none yet. */
	fun resume() {
		if (job?.isActive == true) return
		val current = _progress.value
		if (!current.finished || !current.cancelled) return
		val remaining = synchronized(lock) {
			val tested = allResults.mapTo(HashSet()) { it.sourceKey }
			sourcesByKey.values.filter { it.name !in tested }
		}
		if (remaining.isEmpty()) return
		_progress.update { it.copy(finished = false, cancelled = false, running = emptySet()) }
		runSources(remaining)
	}

	private fun runSources(unique: List<ContentSource>) {
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
	 * Collects a result. The result list is published at most every [PUBLISH_INTERVAL_MS] so rows show up live
	 * without copying the whole list per result; verdicts are persisted in batches of [RESULT_BATCH_SIZE] (or every [FLUSH_INTERVAL_MS])
	 * so a long run does not rewrite prefs per source.
	 */
	private fun onResult(source: ContentSource, result: SourceTestResult) {
		var published: List<SourceTestResult>? = null
		var batch: Map<String, ContentSourceAvailability>? = null
		synchronized(lock) {
			allResults += result
			pendingAvailability[source.name] = result.toAvailability()
			val now = SystemClock.elapsedRealtime()
			if (pendingAvailability.size >= RESULT_BATCH_SIZE || now - lastFlushAt >= FLUSH_INTERVAL_MS) {
				batch = LinkedHashMap(pendingAvailability)
				pendingAvailability.clear()
				lastFlushAt = now
			}
			if (lastPublishAt < 0L || now - lastPublishAt >= PUBLISH_INTERVAL_MS) {
				lastPublishAt = now
				published = allResults.toList()
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
			// A locked source stays as it is: it is neither disabled nor reported as disabled.
			.filter { contentSourcesRepository.lockState(it.name) == null }
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
		/** Batch size of persisted availability verdicts only. */
		const val RESULT_BATCH_SIZE = 20
		const val PUBLISH_INTERVAL_MS = 150L
		const val FLUSH_INTERVAL_MS = 2000L
	}
}
