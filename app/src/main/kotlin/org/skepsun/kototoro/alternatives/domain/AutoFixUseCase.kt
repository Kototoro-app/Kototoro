package org.skepsun.kototoro.alternatives.domain

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.lastOrNull
import kotlinx.coroutines.flow.runningFold
import kotlinx.coroutines.flow.transformWhile
import kotlinx.coroutines.flow.withIndex
import kotlinx.coroutines.launch
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.skepsun.kototoro.core.model.chaptersCount
import org.skepsun.kototoro.core.model.parcelable.ParcelableContent
import org.skepsun.kototoro.core.parser.ContentDataRepository
import org.skepsun.kototoro.core.parser.ContentRepository
import org.skepsun.kototoro.core.util.ext.concat
import org.skepsun.kototoro.parsers.model.Content
import org.skepsun.kototoro.parsers.util.runCatchingCancellable
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException

class AutoFixUseCase @Inject constructor(
	private val mangaRepositoryFactory: ContentRepository.Factory,
	private val alternativesUseCase: AlternativesUseCase,
	private val migrateUseCase: MigrateUseCase,
	private val mangaDataRepository: ContentDataRepository,
) {

	suspend operator fun invoke(mangaId: Long): Pair<Content, Content?> {
		val seed = checkNotNull(
			mangaDataRepository.findPreferredLocalContentById(mangaId, withChapters = true)
				?: mangaDataRepository.findContentById(mangaId, withChapters = true),
		) { "Content $mangaId not found" }.getDetailsSafe()
		if (seed.isHealthy()) {
			return seed to null // no fix required
		}
		val replacement = alternativesUseCase(seed, throughDisabledSources = false)
			.concat(alternativesUseCase(seed, throughDisabledSources = true))
			.filter { it.isHealthy() }
			.runningFold<Content, Content?>(null) { best, candidate ->
				if (best == null || best < candidate) {
					candidate
				} else {
					best
				}
			}.selectLastWithTimeout(4, 40, TimeUnit.SECONDS)
		migrateUseCase(seed, replacement ?: throw NoAlternativesException(ParcelableContent(seed)))
		return seed to replacement
	}

	private suspend fun Content.isHealthy(): Boolean = runCatchingCancellable {
		val repo = mangaRepositoryFactory.create(source)
		val details = if (this.chapters != null) this else repo.getDetails(this)
		val firstChapter = details.chapters?.firstOrNull() ?: return@runCatchingCancellable false
		val pageUrl = repo.getPageUrl(repo.getPages(firstChapter).first())
		pageUrl.toHttpUrlOrNull() != null
	}.getOrDefault(false)

	private suspend fun Content.getDetailsSafe() = runCatchingCancellable {
		mangaRepositoryFactory.create(source).getDetails(this)
	}.getOrDefault(this)

	private operator fun Content.compareTo(other: Content) = chaptersCount().compareTo(other.chaptersCount())

	@Suppress("UNCHECKED_CAST", "OPT_IN_USAGE")
	private suspend fun <T> Flow<T>.selectLastWithTimeout(
		minCount: Int,
		timeout: Long,
		timeUnit: TimeUnit
	): T? = channelFlow<T?> {
		var lastValue: T? = null
		launch {
			delay(timeUnit.toMillis(timeout))
			close(InternalTimeoutException(lastValue))
		}
		withIndex().transformWhile { (index, value) ->
			lastValue = value
			emit(value)
			index < minCount && !isClosedForSend
		}.collect {
			send(it)
		}
	}.catch { e ->
		if (e is InternalTimeoutException) {
			emit(e.value as T?)
		} else {
			throw e
		}
	}.lastOrNull()

	class NoAlternativesException(val seed: ParcelableContent) : NoSuchElementException()

	private class InternalTimeoutException(val value: Any?) : CancellationException()
}
