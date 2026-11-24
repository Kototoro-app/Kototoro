package org.skepsun.kototoro.core.parser

import androidx.annotation.AnyThread
import org.skepsun.kototoro.core.BaseAppHolder
import org.skepsun.kototoro.core.extensions.GlobalExtensionManager
import org.skepsun.kototoro.core.model.ContentSource as resolveContentSourceByName
import org.skepsun.kototoro.core.model.ContentSourceInfo
import org.skepsun.kototoro.parsers.model.ContentSource
import org.skepsun.kototoro.parsers.model.ContentType
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ContentSourceResolutionPipeline internal constructor(
    private val contentSourceInfoResolver: ContentSourceInfoResolver,
    private val jsonContentSourceResolver: JsonContentSourceResolver,
    private val mihonContentSourceResolver: MihonContentSourceResolver,
    private val aniyomiContentSourceResolver: AniyomiContentSourceResolver,
    private val ireaderContentSourceResolver: IReaderContentSourceResolver,
    private val tsundokuContentSourceResolver: TsundokuContentSourceResolver,
    private val sourceFactory: (String) -> ContentSource,
    private val externalEpochProvider: () -> Long,
    private val maxCacheEntries: Int,
) {

    @Inject
    constructor(
        contentSourceInfoResolver: ContentSourceInfoResolver,
        jsonContentSourceResolver: JsonContentSourceResolver,
        mihonContentSourceResolver: MihonContentSourceResolver,
        aniyomiContentSourceResolver: AniyomiContentSourceResolver,
        ireaderContentSourceResolver: IReaderContentSourceResolver,
        tsundokuContentSourceResolver: TsundokuContentSourceResolver,
    ) : this(
        contentSourceInfoResolver = contentSourceInfoResolver,
        jsonContentSourceResolver = jsonContentSourceResolver,
        mihonContentSourceResolver = mihonContentSourceResolver,
        aniyomiContentSourceResolver = aniyomiContentSourceResolver,
        ireaderContentSourceResolver = ireaderContentSourceResolver,
        tsundokuContentSourceResolver = tsundokuContentSourceResolver,
        sourceFactory = ::resolveContentSourceByName,
        externalEpochProvider = ::defaultExternalEpoch,
        maxCacheEntries = DEFAULT_MAX_CACHE_ENTRIES,
    )

    private val resolvers: List<ContentSourceResolver> by lazy(LazyThreadSafetyMode.NONE) {
        listOf(
            contentSourceInfoResolver,
            jsonContentSourceResolver,
            mihonContentSourceResolver,
            aniyomiContentSourceResolver,
            ireaderContentSourceResolver,
            tsundokuContentSourceResolver,
        )
    }

    private val cacheLock = Any()
    private val manualInvalidationVersion = AtomicLong(0L)

    @Volatile
    private var cacheState: CacheState? = null

    data class ResolutionStep(
        val resolver: String,
        val inputSource: String,
        val outputSource: String,
    )

    data class ResolutionResult(
        val resolvedSource: ContentSource,
        val steps: List<ResolutionStep>,
    )

    private data class CacheEpoch(
        val externalEpoch: Long,
        val infoVersion: Long,
        val jsonVersion: Long,
        val mihonVersion: Long,
        val aniyomiVersion: Long,
        val ireaderVersion: Long,
        val tsundokuVersion: Long,
        val manualVersion: Long,
    )

    private data class ResolutionCacheKey(
        val sourceClass: Class<out ContentSource>,
        val name: String,
        val locale: String,
        val contentType: ContentType,
        val wrappedSourceClass: Class<out ContentSource>?,
        val wrappedSourceName: String?,
    )

    private sealed interface CachedResolution {
        data object Unchanged : CachedResolution
        data class Resolved(val result: ResolutionResult) : CachedResolution
    }

    private class CacheState(
        val epoch: CacheEpoch,
        val bySource: ConcurrentHashMap<ResolutionCacheKey, CachedResolution> = ConcurrentHashMap(),
        val byName: ConcurrentHashMap<String, ContentSource> = ConcurrentHashMap(),
    )

    @AnyThread
    fun resolve(source: ContentSource): ContentSource {
        return resolveWithTrace(source).resolvedSource
    }

    @AnyThread
    fun resolveByName(name: String): ContentSource {
        val state = activeCacheState()
        state.byName[name]?.let { return it }
        val resolved = resolveWithTrace(sourceFactory(name)).resolvedSource
        if (matchesCurrentEpoch(state.epoch) && state.byName.size < maxCacheEntries) {
            state.byName.putIfAbsent(name, resolved)
        }
        return resolved
    }

    @AnyThread
    fun resolveWithTrace(source: ContentSource): ResolutionResult {
        val state = activeCacheState()
        val cacheKey = cacheKeyFor(source)
        state.bySource[cacheKey]?.let { cached ->
            return when (cached) {
                CachedResolution.Unchanged -> ResolutionResult(
                    resolvedSource = source,
                    steps = emptyList(),
                )
                is CachedResolution.Resolved -> cached.result
            }
        }
        val result = computeResolutionWithTrace(source)
        if (matchesCurrentEpoch(state.epoch) && state.bySource.size < maxCacheEntries) {
            val entry = if (result.steps.isEmpty() && result.resolvedSource === source) {
                CachedResolution.Unchanged
            } else {
                CachedResolution.Resolved(result)
            }
            state.bySource.putIfAbsent(cacheKey, entry)
        }
        return result
    }

    @AnyThread
    fun invalidate() {
        manualInvalidationVersion.incrementAndGet()
        synchronized(cacheLock) {
            cacheState = null
        }
    }

    private fun computeResolutionWithTrace(source: ContentSource): ResolutionResult {
        var current = source
        val steps = ArrayList<ResolutionStep>()
        while (true) {
            val supportedResolvers = resolvers.filter { resolver -> resolver.supports(current) }
            android.util.Log.d(
                "ContentRepoFactory",
                "stage=resolver_candidates source=${current.name} candidates=${supportedResolvers.joinToString { it::class.simpleName ?: it::class.java.simpleName }}",
            )
            var resolvedBy: String? = null
            val next = supportedResolvers.firstNotNullOfOrNull { resolver ->
                resolver.resolve(current)?.also {
                    resolvedBy = resolver::class.simpleName ?: resolver::class.java.simpleName
                }
            } ?: return ResolutionResult(
                resolvedSource = current,
                steps = steps,
            )
            steps += ResolutionStep(
                resolver = resolvedBy ?: "UnknownResolver",
                inputSource = "${current.name}:${current::class.simpleName}",
                outputSource = "${next.name}:${next::class.simpleName}",
            )
            if (next === current) {
                return ResolutionResult(
                    resolvedSource = current,
                    steps = steps,
                )
            }
            current = next
        }
    }

    private fun activeCacheState(): CacheState {
        val existing = cacheState
        if (existing != null && matchesCurrentEpoch(existing.epoch)) {
            return existing
        }
        return synchronized(cacheLock) {
            val locked = cacheState
            if (locked != null && matchesCurrentEpoch(locked.epoch)) {
                locked
            } else {
                CacheState(captureCurrentEpoch()).also { cacheState = it }
            }
        }
    }

    private fun captureCurrentEpoch(): CacheEpoch {
        return CacheEpoch(
            externalEpoch = externalEpochProvider(),
            infoVersion = contentSourceInfoResolver.cacheVersion(),
            jsonVersion = jsonContentSourceResolver.cacheVersion(),
            mihonVersion = mihonContentSourceResolver.cacheVersion(),
            aniyomiVersion = aniyomiContentSourceResolver.cacheVersion(),
            ireaderVersion = ireaderContentSourceResolver.cacheVersion(),
            tsundokuVersion = tsundokuContentSourceResolver.cacheVersion(),
            manualVersion = manualInvalidationVersion.get(),
        )
    }

    private fun matchesCurrentEpoch(epoch: CacheEpoch): Boolean {
        return epoch.externalEpoch == externalEpochProvider() &&
            epoch.infoVersion == contentSourceInfoResolver.cacheVersion() &&
            epoch.jsonVersion == jsonContentSourceResolver.cacheVersion() &&
            epoch.mihonVersion == mihonContentSourceResolver.cacheVersion() &&
            epoch.aniyomiVersion == aniyomiContentSourceResolver.cacheVersion() &&
            epoch.ireaderVersion == ireaderContentSourceResolver.cacheVersion() &&
            epoch.tsundokuVersion == tsundokuContentSourceResolver.cacheVersion() &&
            epoch.manualVersion == manualInvalidationVersion.get()
    }

    private fun cacheKeyFor(source: ContentSource): ResolutionCacheKey {
        val wrapped = (source as? ContentSourceInfo)?.mangaSource
        return ResolutionCacheKey(
            sourceClass = source::class.java,
            name = source.name,
            locale = source.locale,
            contentType = source.contentType,
            wrappedSourceClass = wrapped?.let { it::class.java },
            wrappedSourceName = wrapped?.name,
        )
    }

    private companion object {
        const val DEFAULT_MAX_CACHE_ENTRIES = 1024

        fun defaultExternalEpoch(): Long {
            val globalVersion = GlobalExtensionManager.version.toLong()
            val cloudstreamVersion = (BaseAppHolder.get()?.version ?: -1).toLong()
            return (globalVersion shl 32) xor (cloudstreamVersion and 0xffffffffL)
        }
    }
}
