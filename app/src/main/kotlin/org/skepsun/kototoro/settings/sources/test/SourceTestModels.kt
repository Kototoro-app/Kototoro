package org.skepsun.kototoro.settings.sources.test

internal const val SOURCE_TEST_STEP_TIMEOUT_MS = 20_000L
internal const val SOURCE_TEST_SOURCE_TIMEOUT_MS = 90_000L
internal const val SOURCE_TEST_MAX_PARALLELISM = 10
internal const val SOURCE_TEST_COVER_SAMPLE = 3
internal const val SOURCE_TEST_DETAILS_SAMPLE = 3
internal val SOURCE_TEST_SEARCH_QUERIES = listOf("a", "one")

enum class SourceTestStep {
	LIST,
	COVERS,
	DETAILS,
	CHAPTERS,
	PAGES,
	RANDOM,
	SEARCH,
}

enum class StepStatus {
	PASS,
	FAIL,
	SKIP,
	CLOUDFLARE,
}

data class StepResult(
	val status: StepStatus,
	val message: String = "",
	val durationMs: Long = 0L,
)

/**
 * Overall verdict for a source: a single failed step fails the source,
 * while PASS and SKIP steps together pass it.
 */
internal fun overallStatusOf(steps: Map<SourceTestStep, StepResult>): StepStatus =
	when {
		steps.values.any { it.status == StepStatus.FAIL } -> StepStatus.FAIL
		steps.values.any { it.status == StepStatus.CLOUDFLARE } -> StepStatus.CLOUDFLARE
		else -> StepStatus.PASS
	}

data class SourceTestResult(
	val sourceKey: String,
	val title: String,
	val steps: Map<SourceTestStep, StepResult>,
	val overall: StepStatus = overallStatusOf(steps),
	val durationMs: Long = 0L,
)

/** Live state of a "test all" run. */
data class TestProgress(
	val done: Int = 0,
	val total: Int = 0,
	val running: Set<String> = emptySet(),
	val results: List<SourceTestResult> = emptyList(),
	val disabledKeys: Set<String> = emptySet(),
	val finished: Boolean = false,
	val cancelled: Boolean = false,
	val errorMessage: String? = null,
)
