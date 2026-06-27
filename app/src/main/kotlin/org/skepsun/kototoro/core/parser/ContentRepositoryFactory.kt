package org.skepsun.kototoro.core.parser

import androidx.annotation.AnyThread
import org.skepsun.kototoro.core.extensions.GlobalExtensionManager
import org.skepsun.kototoro.core.model.UnknownContentSource
import org.skepsun.kototoro.core.parser.external.ExternalContentSource
import org.skepsun.kototoro.parsers.model.ContentSource
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ContentRepositoryFactory @Inject constructor(
	private val sourceResolutionPipeline: ContentSourceResolutionPipeline,
	private val repositoryProviderRegistry: ContentRepositoryProviderRegistry,
	private val repositoryInstanceCache: ContentRepositoryInstanceCache,
) {
	private val cacheVersionLock = Any()
	private var cachedGlobalExtensionVersion = GlobalExtensionManager.version

	enum class ResolutionStatus {
		UNCHANGED,
		RESOLVED,
	}

	enum class ProviderStatus {
		SELECTED,
		FALLBACK_EMPTY,
		SKIPPED_BY_CACHE,
	}

	enum class CacheStatus {
		HIT,
		MISS,
	}

	enum class FailureReason {
		UNKNOWN_SOURCE,
		UNAVAILABLE_EXTERNAL_SOURCE,
		NO_SUPPORTED_PROVIDER,
		NO_PROVIDER_PRODUCED_REPOSITORY,
	}

	data class CreationResult(
		val requestedSource: ContentSource,
		val resolvedSource: ContentSource,
		val repository: ContentRepository,
		val resolutionStatus: ResolutionStatus,
		val providerStatus: ProviderStatus,
		val cacheStatus: CacheStatus,
		val selectedProvider: String?,
		val candidateProviders: List<String>,
		val attemptedProviders: List<String>,
		val resolutionTrace: List<ContentSourceResolutionPipeline.ResolutionStep>,
		val failureReason: FailureReason?,
	) {
		val cacheHit: Boolean
			get() = cacheStatus == CacheStatus.HIT
	}

	@AnyThread
	fun create(source: ContentSource): ContentRepository {
		return createWithDiagnostics(source).repository
	}

	@AnyThread
	fun createWithDiagnostics(source: ContentSource): CreationResult {
		invalidateCacheIfGlobalExtensionsChanged()
		android.util.Log.d(
			"ContentRepoFactory",
			"stage=create_start source=${source.name} sourceType=${source::class.simpleName}",
		)
		val resolutionResult = sourceResolutionPipeline.resolveWithTrace(source)
		val resolvedSource = resolutionResult.resolvedSource
		val resolutionSummary = resolutionResult.steps.joinToString(" -> ") {
			"${it.resolver}[${it.inputSource}=>${it.outputSource}]"
		}.ifBlank { "unchanged" }
		android.util.Log.d(
			"ContentRepoFactory",
			"stage=source_resolved source=${source.name} resolved=${resolvedSource.name} resolvedType=${resolvedSource::class.simpleName} trace=$resolutionSummary",
		)
		val resolutionStatus = if (resolutionResult.steps.isEmpty()) {
			ResolutionStatus.UNCHANGED
		} else {
			ResolutionStatus.RESOLVED
		}
		var selectionResult: ContentRepositoryProviderRegistry.SelectionResult? = null
		var failureReason: FailureReason? = null
		val cacheResult = repositoryInstanceCache.getOrPutWithResult(
			source = resolvedSource,
			shouldCache = { repository -> repository !is EmptyContentRepository },
		) {
			val selection = repositoryProviderRegistry.select(resolvedSource)
			selectionResult = selection
			if (selection.repository != null) {
				android.util.Log.d(
					"ContentRepoFactory",
					"stage=provider_selected source=${resolvedSource.name} provider=${selection.selectedProvider} repository=${selection.repository::class.simpleName} attempts=${selection.attemptedProviders.joinToString()}",
				)
				selection.repository
			} else {
				android.util.Log.w(
					"ContentRepoFactory",
					"stage=provider_fallback_empty source=${resolvedSource.name} attempts=${selection.attemptedProviders.joinToString()}",
				)
				EmptyContentRepository(resolvedSource)
			}
		}
		val cacheStatus = if (cacheResult.cacheHit) CacheStatus.HIT else CacheStatus.MISS
		failureReason = determineFailureReason(
			resolvedSource = resolvedSource,
			repository = cacheResult.repository,
			selectionResult = selectionResult,
			currentFailureReason = failureReason,
		)
		val providerStatus = when {
			cacheResult.cacheHit -> ProviderStatus.SKIPPED_BY_CACHE
			failureReason != null -> ProviderStatus.FALLBACK_EMPTY
			else -> ProviderStatus.SELECTED
		}
		android.util.Log.d(
			"ContentRepoFactory",
			"stage=cache_${cacheStatus.name.lowercase()} source=${resolvedSource.name} repository=${cacheResult.repository::class.simpleName}",
		)
		if (cacheResult.cacheHit && selectionResult == null) {
			android.util.Log.d(
				"ContentRepoFactory",
				"stage=diagnostic_cache_reuse source=${resolvedSource.name} note=provider_selection_skipped_due_to_cache_hit",
			)
		}
		return CreationResult(
			requestedSource = source,
			resolvedSource = resolvedSource,
			repository = cacheResult.repository,
			resolutionStatus = resolutionStatus,
			providerStatus = providerStatus,
			cacheStatus = cacheStatus,
			selectedProvider = selectionResult?.selectedProvider,
			candidateProviders = selectionResult?.candidateProviders.orEmpty(),
			attemptedProviders = selectionResult?.attemptedProviders.orEmpty(),
			resolutionTrace = resolutionResult.steps,
			failureReason = failureReason,
		)
	}

	private fun invalidateCacheIfGlobalExtensionsChanged() {
		val currentVersion = GlobalExtensionManager.version
		if (cachedGlobalExtensionVersion == currentVersion) {
			return
		}
		synchronized(cacheVersionLock) {
			if (cachedGlobalExtensionVersion != currentVersion) {
				repositoryInstanceCache.clear()
				cachedGlobalExtensionVersion = currentVersion
			}
		}
	}

	private fun determineFailureReason(
		resolvedSource: ContentSource,
		repository: ContentRepository,
		selectionResult: ContentRepositoryProviderRegistry.SelectionResult?,
		currentFailureReason: FailureReason?,
	): FailureReason? {
		if (repository !is EmptyContentRepository) {
			return null
		}
		if (resolvedSource == UnknownContentSource) {
			return FailureReason.UNKNOWN_SOURCE
		}
		if (resolvedSource is ExternalContentSource) {
			return FailureReason.UNAVAILABLE_EXTERNAL_SOURCE
		}
		if (selectionResult == null) {
			return currentFailureReason
		}
		if (selectionResult.candidateProviders.isEmpty()) {
			return FailureReason.NO_SUPPORTED_PROVIDER
		}
		if (selectionResult.selectedProvider == null) {
			return FailureReason.NO_PROVIDER_PRODUCED_REPOSITORY
		}
		return currentFailureReason
	}
}
