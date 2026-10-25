package org.skepsun.kototoro.desktop.app

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import org.skepsun.kototoro.core.source.SourceChallengeException
import org.skepsun.kototoro.core.source.SourceChapter
import org.skepsun.kototoro.core.source.SourceContent
import org.skepsun.kototoro.core.source.SourceDetailsFetchMode
import org.skepsun.kototoro.core.source.SourceErrorCode
import org.skepsun.kototoro.core.source.SourceFilter
import org.skepsun.kototoro.core.source.SourceRemoteException
import org.skepsun.kototoro.core.source.SourceRuntime

/** Android's `SourceTestStep`, one per thing a source can be asked for. */
internal enum class SourceTestStep { LIST, COVERS, DETAILS, CHAPTERS, PAGES, SEARCH }

/** Android's `StepStatus`; [CHALLENGE] is Android's `CLOUDFLARE` under a runtime-neutral name. */
internal enum class SourceTestStatus { PASS, FAIL, SKIP, CHALLENGE }

internal data class SourceTestStepResult(
    val status: SourceTestStatus,
    val message: String,
    val durationMs: Long,
)

internal data class SourceTestResult(
    val sourceName: String,
    val title: String,
    val steps: Map<SourceTestStep, SourceTestStepResult>,
    val durationMs: Long,
) {
    /** Android's `overallStatusOf`: the worst verdict a step reported. */
    val overall: SourceTestStatus
        get() = when {
            steps.values.any { it.status == SourceTestStatus.FAIL } -> SourceTestStatus.FAIL
            steps.values.any { it.status == SourceTestStatus.CHALLENGE } -> SourceTestStatus.CHALLENGE
            else -> SourceTestStatus.PASS
        }
}

/** Which sources a run covers: the source name the runtime knows plus the label the UI shows for it. */
internal data class SourceTestTarget(val sourceName: String, val title: String)

internal data class SourceTestProgress(
    val done: Int = 0,
    val total: Int = 0,
    val running: Set<String> = emptySet(),
    val results: List<SourceTestResult> = emptyList(),
    val finished: Boolean = false,
    val cancelled: Boolean = false,
) {
    val isRunning: Boolean get() = total > 0 && !finished

    /** Android's `disableFailed`: the sources of this run whose verdict is FAIL. */
    val failedSources: List<SourceTestResult> get() = results.filter { it.overall == SourceTestStatus.FAIL }
}

/**
 * Android's `SourceTester`: asks every available source for a list, a cover, the details of the first work, its
 * chapters, the pages of its first chapter and a search result, and reports per-step verdicts instead of stopping at
 * the first failure. One dead source must not hide the others, which is why the steps of a source and the sources of a
 * run both tolerate failures.
 *
 * Two of Android's seven steps have no Windows operation and are not faked:
 * - RANDOM: the desktop runtime exposes no random operation (the `SourceRuntime` contract in `core-source` has none),
 *   so there is nothing to ask. The browse page's shuffle is a client-side reorder of a loaded page.
 * - A source that answers a step with UNSUPPORTED_OPERATION reads SKIP, not FAIL, exactly like Android.
 *
 * Step and run deadlines are Android's own (`SOURCE_TEST_STEP_TIMEOUT_MS`/`SOURCE_TEST_SOURCE_TIMEOUT_MS`): a source
 * that hangs is a verdict, not a stuck page. Progress lives in [progress], not in `DesktopAppState`, so the page can
 * show a live run without the rest of the window rebuilding (the GitHub mirror sweep's pattern, plan §162).
 */
internal class DesktopSourceTester(
    private val sources: SourceRuntime,
    /** Loads one cover through the same pipeline the grids use, so a COVERS pass means the image really landed. */
    private val loadCover: suspend (SourceContent) -> Unit,
    /** Probes one stream URL; null when it answered, the reason otherwise. */
    private val probeUrl: suspend (String) -> String?,
    /** The query the SEARCH step sends, Android's first (`listOf("a", "one")`). */
    private val searchQuery: String = "a",
) {
    private val mutableProgress = MutableStateFlow(SourceTestProgress())
    val progress: StateFlow<SourceTestProgress> = mutableProgress.asStateFlow()

    private val lock = Mutex()

    /** Forgets the previous run; the page calls it on entry so a re-entry starts clean instead of showing stale rows. */
    fun reset() {
        if (mutableProgress.value.isRunning) return
        mutableProgress.value = SourceTestProgress()
    }

    /**
     * Tests every target, at most [MAX_PARALLELISM] at a time (Android's semaphore). Results keep the order the caller
     * passed so the list does not reshuffle while it fills up.
     */
    suspend fun testAll(targets: List<SourceTestTarget>) {
        if (targets.isEmpty()) return
        if (mutableProgress.value.isRunning) return
        mutableProgress.value = SourceTestProgress(total = targets.size)
        val completed = LinkedHashMap<String, SourceTestResult>(targets.size)
        val semaphore = Semaphore(MAX_PARALLELISM)
        try {
            coroutineScope {
                targets.map { target ->
                    async {
                        semaphore.withPermit {
                            publish { it.copy(running = it.running + target.sourceName) }
                            val result = test(target)
                            lock.withLock {
                                completed[target.sourceName] = result
                                val ordered = targets.mapNotNull { completed[it.sourceName] }
                                publish {
                                    it.copy(done = ordered.size, running = it.running - target.sourceName,
                                        results = ordered)
                                }
                            }
                        }
                    }
                }.awaitAll()
            }
            publish { it.copy(finished = true, running = emptySet()) }
        } finally {
            // Cancelled: keep the rows that finished, report the run as over instead of leaving a spinner behind.
            if (!mutableProgress.value.finished) {
                publish { it.copy(finished = true, cancelled = true, running = emptySet()) }
            }
        }
    }

    /** Tests one source over all steps. The run deadline is Android's 90 s; unfinished steps read as a timeout. */
    suspend fun test(target: SourceTestTarget): SourceTestResult {
        val startedAt = System.nanoTime()
        val steps = LinkedHashMap<SourceTestStep, SourceTestStepResult>()
        var listed: List<SourceContent> = emptyList()
        var details: SourceContent? = null
        var chapter: SourceChapter? = null
        val deadline = withTimeoutOrNull(SOURCE_TIMEOUT_MS) {
            steps[SourceTestStep.LIST] = runStep(SourceTestStep.LIST) {
                listed = sources.getList(target.sourceName, 0, null, null)
                if (listed.isEmpty()) {
                    verdict(SourceTestStatus.FAIL, AndroidStrings["source_test_msg_empty"])
                } else {
                    verdict(SourceTestStatus.PASS, AndroidStrings.format("source_test_msg_count", listed.size))
                }
            }
            if (listed.isEmpty()) return@withTimeoutOrNull false
            steps[SourceTestStep.COVERS] = runStep(SourceTestStep.COVERS) {
                val sample = listed.take(COVER_SAMPLE)
                val errors = sample.mapNotNull { content -> runCatching { loadCover(content) }.exceptionOrNull() }
                if (errors.isNotEmpty()) {
                    verdict(SourceTestStatus.FAIL, AndroidStrings.format("source_test_msg_covers_failed",
                        sample.size - errors.size, sample.size, errors.first().reason()))
                } else {
                    verdict(SourceTestStatus.PASS,
                        AndroidStrings.format("source_test_msg_covers", sample.size, sample.size))
                }
            }
            val first = listed.first()
            steps[SourceTestStep.DETAILS] = runStep(SourceTestStep.DETAILS) {
                details = sources.getDetails(first, SourceDetailsFetchMode.FORCE_REFRESH)
                val detail = details
                if (detail == null || detail.title.isBlank()) {
                    verdict(SourceTestStatus.FAIL, AndroidStrings["source_test_msg_no_items"])
                } else {
                    verdict(SourceTestStatus.PASS, AndroidStrings.format("source_test_msg_details", detail.title))
                }
            }
            val chapters = details?.chapters.orEmpty()
            steps[SourceTestStep.CHAPTERS] = runStep(SourceTestStep.CHAPTERS) {
                if (chapters.isEmpty()) {
                    verdict(SourceTestStatus.FAIL, AndroidStrings["source_test_msg_no_chapters"])
                } else {
                    verdict(SourceTestStatus.PASS, AndroidStrings.format("source_test_msg_chapters", chapters.size))
                }
            }
            chapter = chapters.firstOrNull()
            if (chapter != null) {
                val only = requireNotNull(chapter)
                steps[SourceTestStep.PAGES] = runStep(SourceTestStep.PAGES) {
                    pagesVerdict(requireNotNull(details ?: first), only)
                }
            }
            steps[SourceTestStep.SEARCH] = runStep(SourceTestStep.SEARCH) {
                val capabilities = sources.describe(target.sourceName).filterCapabilities
                if (!capabilities.isSearchSupported) {
                    verdict(SourceTestStatus.SKIP, AndroidStrings["source_test_msg_search_unsupported"])
                } else {
                    val hits = sources.getList(target.sourceName, 0, null, SourceFilter(query = searchQuery))
                    if (hits.isEmpty()) {
                        verdict(SourceTestStatus.FAIL,
                            AndroidStrings.format("source_test_msg_search_empty", searchQuery))
                    } else {
                        verdict(SourceTestStatus.PASS,
                            AndroidStrings.format("source_test_msg_search_hit", searchQuery, hits.size))
                    }
                }
            }
            true
        }
        // A step the run never reached reads as "skipped because an earlier step failed", Android's wording; after the
        // overall deadline expired the same holes are timeouts instead, which is what the user needs to know then.
        SourceTestStep.entries.forEach { step ->
            if (step !in steps) {
                steps[step] = if (deadline == null) {
                    SourceTestStepResult(SourceTestStatus.FAIL,
                        AndroidStrings.format("source_test_msg_total_timeout", SOURCE_TIMEOUT_MS / 1_000), 0L)
                } else {
                    SourceTestStepResult(SourceTestStatus.SKIP, AndroidStrings["source_test_msg_prev_failed"], 0L)
                }
            }
        }
        return SourceTestResult(target.sourceName, target.title, steps, (System.nanoTime() - startedAt) / 1_000_000L)
    }

    /**
     * Android's PAGES step, split by content type because the three readers consume different artifacts: pages of a
     * manga are images, a novel chapter is text with optional illustrations, and a video episode is a stream URL.
     */
    private suspend fun pagesVerdict(content: SourceContent, chapter: SourceChapter): SourceTestStepResult {
        val next = chapter.url.takeIf { it.isNotBlank() }
        return when (DesktopReaderKind.of(content)) {
            DesktopReaderKind.PAGES -> {
                val pages = sources.getPages(chapter, next)
                if (pages.isEmpty()) {
                    verdict(SourceTestStatus.FAIL, AndroidStrings["source_test_msg_no_pages"])
                } else {
                    // One image proves the chapter is readable; fetching all of them would download a whole chapter.
                    runCatching { sources.fetchImage(pages.first()) }.fold(
                        onSuccess = {
                            verdict(SourceTestStatus.PASS,
                                AndroidStrings.format("source_test_msg_pages", pages.size))
                        },
                        onFailure = { error ->
                            failure(error, AndroidStrings.format("source_test_msg_image_failed", error.reason()))
                        },
                    )
                }
            }
            DesktopReaderKind.NOVEL -> {
                val body = sources.getChapterContent(chapter, next)
                if (body == null || body.html.isBlank()) {
                    verdict(SourceTestStatus.FAIL, AndroidStrings["source_test_msg_empty_body"])
                } else {
                    verdict(SourceTestStatus.PASS, AndroidStrings["source_test_msg_text_chapter"])
                }
            }
            DesktopReaderKind.VIDEO -> {
                val streams = sources.getPages(chapter, next).filter { it.url.isNotBlank() }
                if (streams.isEmpty()) {
                    verdict(SourceTestStatus.FAIL, AndroidStrings["source_test_msg_no_pages"])
                } else {
                    val url = sources.getPageUrl(streams.first())
                    val reason = probeUrl(url)
                    if (reason == null) {
                        verdict(SourceTestStatus.PASS,
                            AndroidStrings.format("source_test_msg_video_stream", streams.size))
                    } else {
                        verdict(SourceTestStatus.FAIL,
                            AndroidStrings.format("source_test_msg_stream_failed", reason))
                    }
                }
            }
        }
    }

    /**
     * Runs one step under Android's per-step deadline, turning whatever went wrong into that step's verdict. A
     * challenge is reported, not opened: a page that shows a browser window mid-run would need a human in front of a
     * test that is supposed to run unattended.
     */
    private suspend fun runStep(step: SourceTestStep, block: suspend () -> SourceTestStepResult): SourceTestStepResult {
        val startedAt = System.nanoTime()
        val timeout = AndroidStrings.format("source_test_msg_timeout", STEP_TIMEOUT_MS / 1_000)
        val result = withTimeoutOrNull(STEP_TIMEOUT_MS) {
            try {
                block()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                failure(error, error.reason())
            }
        } ?: SourceTestStepResult(SourceTestStatus.FAIL, timeout, 0L)
        return result.copy(durationMs = (System.nanoTime() - startedAt) / 1_000_000L).also {
            println("DESKTOP_SOURCE_TEST step=${step.name} status=${it.status} ms=${it.durationMs} ${it.message}")
        }
    }

    /** Maps a source failure onto a verdict: unsupported SKIPs, a challenge is its own verdict, the rest FAIL. */
    private fun failure(error: Throwable, message: String): SourceTestStepResult = when {
        error is SourceRemoteException && error.error.code == SourceErrorCode.UNSUPPORTED_OPERATION ->
            verdict(SourceTestStatus.SKIP, AndroidStrings["source_test_msg_unsupported"])
        error.isChallenge() -> verdict(SourceTestStatus.CHALLENGE, AndroidStrings["source_test_msg_cloudflare"])
        else -> verdict(SourceTestStatus.FAIL, message)
    }

    private fun verdict(status: SourceTestStatus, message: String) =
        SourceTestStepResult(status, message, 0L)

    private fun publish(block: (SourceTestProgress) -> SourceTestProgress) {
        mutableProgress.value = block(mutableProgress.value)
    }

    private companion object {
        /** Android's `SOURCE_TEST_STEP_TIMEOUT_MS`. */
        const val STEP_TIMEOUT_MS = 20_000L

        /** Android's `SOURCE_TEST_SOURCE_TIMEOUT_MS`: a source with six steps cannot outlive this. */
        const val SOURCE_TIMEOUT_MS = 90_000L

        /** Android's desktop and phone both keep the run to three sources at a time instead of a source host swarm. */
        const val MAX_PARALLELISM = 3

        /** Android's `SOURCE_TEST_COVER_SAMPLE`: three covers prove the pipeline without downloading a shelf. */
        const val COVER_SAMPLE = 3
    }
}

/** The cause chain, not just the surfaced exception: a runtime wraps a challenge inside its own error. */
private fun Throwable.isChallenge(): Boolean {
    var current: Throwable? = this
    val seen = HashSet<Throwable>()
    while (current != null && seen.add(current)) {
        if (current is SourceChallengeException) return true
        if (current is SourceRemoteException && current.error.code == SourceErrorCode.CHALLENGE_REQUIRED) return true
        current = current.cause
    }
    return false
}

/** The one line a failure contributes: the message the runtime gave, falling back to its simple class name. */
private fun Throwable.reason(): String =
    message?.takeIf { it.isNotBlank() } ?: javaClass.simpleName

/**
 * The run as Android's "export" JSON: the same keys, the same lower-case statuses, messages only where they explain a
 * failure — a bug report can be compared across the two platforms line by line.
 */
internal fun SourceTestProgress.toExportJson(): String {
    val items = results.joinToString(",") { result ->
        val steps = result.steps.entries.joinToString(",") { (step, stepResult) ->
            "\"${step.name}\":\"${stepResult.status.name.lowercase()}\""
        }
        val messages = result.steps.entries
            .filter { it.value.message.isNotBlank() && it.value.status != SourceTestStatus.PASS &&
                it.value.status != SourceTestStatus.SKIP }
            .joinToString(",") { (step, stepResult) ->
                "\"${step.name}\":\"${stepResult.message.jsonEscaped()}\""
            }
        val messagesObject = if (messages.isEmpty()) "" else ",\"messages\":{$messages}"
        "{\"name\":\"${result.sourceName.jsonEscaped()}\"," +
            "\"status\":\"${result.overall.name.lowercase()}\",\"steps\":{$steps}$messagesObject}"
    }
    return "{\"generated\":\"${java.time.Instant.now()}\",\"sources\":[$items]}"
}

private fun String.jsonEscaped(): String = buildString {
    this@jsonEscaped.forEach { character ->
        when (character) {
            '\\' -> append("\\\\")
            '"' -> append("\\\"")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> if (character < ' ') append("\\u%04x".format(character.code)) else append(character)
        }
    }
}
