package org.skepsun.kototoro.settings.sources.test

import android.content.Context
import android.graphics.BitmapFactory
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Response
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.LocalizedAppContext
import org.skepsun.kototoro.core.exceptions.CloudFlareException
import org.skepsun.kototoro.core.model.getTitle
import org.skepsun.kototoro.core.network.ContentHttpClient
import org.skepsun.kototoro.core.parser.ContentRepository
import org.skepsun.kototoro.parsers.model.Content
import org.skepsun.kototoro.parsers.model.ContentListFilter
import org.skepsun.kototoro.parsers.model.ContentSource
import org.skepsun.kototoro.parsers.util.await
import org.skepsun.kototoro.parsers.util.runCatchingCancellable
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Runs the reader flow (list, covers, details, chapters, pages, random, search) for one source.
 * Works for every source type through [ContentRepository.Factory].
 */
@Singleton
class SourceTester @Inject constructor(
	@LocalizedAppContext private val context: Context,
	private val repositoryFactory: ContentRepository.Factory,
	@ContentHttpClient private val okHttp: OkHttpClient,
) {

	/**
	 * [onStep] is called as soon as each step finishes, so callers can show partial results.
	 * Steps that never ran because the source deadline expired are recorded as FAIL.
	 */
	suspend fun test(
		source: ContentSource,
		onStep: (SourceTestStep, StepResult) -> Unit = { _, _ -> },
	): SourceTestResult {
		val startedAt = System.nanoTime()
		val steps = LinkedHashMap<SourceTestStep, StepResult>()
		val record: (SourceTestStep, StepResult) -> Unit = { step, result ->
			steps[step] = result
			onStep(step, result)
		}
		val repo = runCatchingCancellable { repositoryFactory.create(source) }.getOrNull()
		if (repo == null) {
			SourceTestStep.entries.forEach { record(it, failed(R.string.source_test_msg_no_repo)) }
		} else {
			val completed = withTimeoutOrNull(SOURCE_TEST_SOURCE_TIMEOUT_MS) { runFlow(repo, record) } != null
			if (!completed) {
				SourceTestStep.entries.forEach { step ->
					if (step !in steps) {
						record(
							step,
							failed(R.string.source_test_msg_total_timeout, SOURCE_TEST_SOURCE_TIMEOUT_MS / 1000),
						)
					}
				}
			}
		}
		return SourceTestResult(
			sourceKey = source.name,
			title = source.getTitle(context),
			steps = steps.toMap(),
			durationMs = elapsedMs(startedAt),
		)
	}

	private suspend fun runFlow(repo: ContentRepository, record: (SourceTestStep, StepResult) -> Unit) {
		var items: List<Content> = emptyList()
		val listOk = runStep(SourceTestStep.LIST, record) {
			items = repo.getList(0, repo.defaultSortOrder, ContentListFilter.EMPTY)
			if (items.isEmpty()) {
				failed(R.string.source_test_msg_empty)
			} else {
				passed(R.string.source_test_msg_count, items.size)
			}
		}

		val candidates = items.take(SOURCE_TEST_COVER_SAMPLE)
		runOrSkip(SourceTestStep.COVERS, record, listOk) {
			if (candidates.isEmpty()) return@runOrSkip failed(R.string.source_test_msg_no_items)
			val client = repo.getImageClient() ?: okHttp
			val errors = candidates.mapNotNull { content ->
				val url = content.coverUrl ?: return@mapNotNull text(R.string.source_test_msg_no_cover)
				val response = client.newCall(repo.createCoverRequest(url)).await()
				verifyImage(response)
			}
			if (errors.isEmpty()) {
				passed(R.string.source_test_msg_covers, candidates.size, candidates.size)
			} else {
				failed(
					R.string.source_test_msg_covers_failed,
					candidates.size - errors.size,
					candidates.size,
					errors.first(),
				)
			}
		}

		var details: Content? = null
		runOrSkip(SourceTestStep.DETAILS, record, listOk) {
			// The first list entry can legitimately have no chapters (announcements, empty series),
			// so try a few entries before declaring the details/chapters steps failed.
			val sample = items.take(SOURCE_TEST_DETAILS_SAMPLE)
			if (sample.isEmpty()) return@runOrSkip failed(R.string.source_test_msg_no_items)
			var firstError: Throwable? = null
			for (item in sample) {
				val attempt = runCatchingCancellable { repo.getDetails(item) }
				val loaded = attempt.getOrNull()
				if (loaded == null) {
					if (firstError == null) firstError = attempt.exceptionOrNull()
					continue
				}
				details = loaded
				if (loaded.chapters.orEmpty().isNotEmpty()) break
			}
			val loaded = details ?: throw (firstError ?: IllegalStateException("details"))
			passed(R.string.source_test_msg_details, loaded.title)
		}

		val chapter = details?.chapters?.firstOrNull()
		runOrSkip(SourceTestStep.CHAPTERS, record, details != null) {
			val count = details?.chapters.orEmpty().size
			if (count == 0) {
				failed(R.string.source_test_msg_no_chapters)
			} else {
				passed(R.string.source_test_msg_chapters, count)
			}
		}

		runOrSkip(SourceTestStep.PAGES, record, chapter != null) {
			val target = chapter ?: return@runOrSkip failed(R.string.source_test_msg_no_chapters)
			val pages = repo.getPages(target)
			if (pages.isEmpty()) {
				if (repo.getChapterContent(target) != null) {
					return@runOrSkip passed(R.string.source_test_msg_text_chapter)
				}
				return@runOrSkip failed(R.string.source_test_msg_no_pages)
			}
			val page = pages.first()
			val pageUrl = repo.getPageUrl(page)
			val response = repo.fetchPageResponse(pageUrl, page)
				?: (repo.getImageClient() ?: okHttp).newCall(repo.createPageRequest(pageUrl, page)).await()
			val error = verifyImage(response)
			if (error == null) {
				passed(R.string.source_test_msg_pages, pages.size)
			} else {
				failed(R.string.source_test_msg_image_failed, error)
			}
		}

		// No per-source random capability exists in the parser API (only app-level random suggestions).
		record(SourceTestStep.RANDOM, skipped(R.string.source_test_msg_unsupported))

		if (!repo.filterCapabilities.isSearchSupported) {
			record(SourceTestStep.SEARCH, skipped(R.string.source_test_msg_search_unsupported))
		} else {
			runStep(SourceTestStep.SEARCH, record) {
				val hit = SOURCE_TEST_SEARCH_QUERIES.firstNotNullOfOrNull { query ->
					val count = repo.getList(0, repo.defaultSortOrder, ContentListFilter(query = query)).size
					if (count > 0) query to count else null
				}
				if (hit == null) {
					failed(R.string.source_test_msg_search_empty, SOURCE_TEST_SEARCH_QUERIES.joinToString())
				} else {
					passed(R.string.source_test_msg_search_hit, hit.first, hit.second)
				}
			}
		}
	}

	/** Runs [block] under the per-step timeout, records the timed result and returns true on PASS. */
	private suspend fun runStep(
		step: SourceTestStep,
		record: (SourceTestStep, StepResult) -> Unit,
		block: suspend () -> StepResult,
	): Boolean {
		val startedAt = System.nanoTime()
		val result = withTimeoutOrNull(SOURCE_TEST_STEP_TIMEOUT_MS) {
			runCatchingCancellable { block() }.getOrElse { e ->
				if (isCloudflareBlocked(e)) {
					StepResult(StepStatus.CLOUDFLARE, text(R.string.source_test_msg_cloudflare))
				} else {
					StepResult(StepStatus.FAIL, describe(e))
				}
			}
		} ?: failed(R.string.source_test_msg_timeout, SOURCE_TEST_STEP_TIMEOUT_MS / 1000)
		val timed = result.copy(durationMs = elapsedMs(startedAt))
		record(step, timed)
		return timed.status == StepStatus.PASS
	}

	private suspend fun runOrSkip(
		step: SourceTestStep,
		record: (SourceTestStep, StepResult) -> Unit,
		ready: Boolean,
		block: suspend () -> StepResult,
	): Boolean {
		if (ready) {
			return runStep(step, record, block)
		}
		record(step, skipped(R.string.source_test_msg_prev_failed))
		return false
	}

	/** Returns null when the response is HTTP 200 and holds an image, otherwise a short reason. */
	private fun verifyImage(response: Response): String? = response.use { r ->
		if (!r.isSuccessful) return@use "HTTP ${r.code}"
		val bytes = r.body?.bytes()
		if (bytes == null || bytes.isEmpty()) return@use text(R.string.source_test_msg_empty_body)
		val contentType = r.header("Content-Type").orEmpty()
		if (contentType.startsWith("image/", ignoreCase = true)) return@use null
		val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
		BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
		if (bounds.outWidth > 0 && bounds.outHeight > 0) {
			null
		} else {
			"Content-Type: ${contentType.ifEmpty { "-" }}"
		}
	}

	private fun isCloudflareBlocked(e: Throwable): Boolean {
		var current: Throwable? = e
		var depth = 0
		while (current != null && depth < 5) {
			if (current is CloudFlareException) return true
			current = current.cause
			depth++
		}
		return false
	}

	private fun describe(e: Throwable): String {
		val message = e.message?.take(160)
		return if (message.isNullOrBlank()) e.javaClass.simpleName else "${e.javaClass.simpleName}: $message"
	}

	private fun text(id: Int, vararg args: Any): String = context.getString(id, *args)

	private fun passed(id: Int, vararg args: Any) = StepResult(StepStatus.PASS, text(id, *args))

	private fun failed(id: Int, vararg args: Any) = StepResult(StepStatus.FAIL, text(id, *args))

	private fun skipped(id: Int, vararg args: Any) = StepResult(StepStatus.SKIP, text(id, *args))

	private fun elapsedMs(startedAt: Long): Long = (System.nanoTime() - startedAt) / 1_000_000
}
