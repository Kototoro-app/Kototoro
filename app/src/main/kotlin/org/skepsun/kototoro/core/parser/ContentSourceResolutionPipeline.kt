package org.skepsun.kototoro.core.parser

import org.skepsun.kototoro.parsers.model.ContentSource
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ContentSourceResolutionPipeline @Inject constructor(
    private val contentSourceInfoResolver: ContentSourceInfoResolver,
    private val jsonContentSourceResolver: JsonContentSourceResolver,
    private val mihonContentSourceResolver: MihonContentSourceResolver,
    private val aniyomiContentSourceResolver: AniyomiContentSourceResolver,
    private val ireaderContentSourceResolver: IReaderContentSourceResolver,
) {

    private val resolvers: List<ContentSourceResolver> by lazy(LazyThreadSafetyMode.NONE) {
        listOf(
            contentSourceInfoResolver,
            jsonContentSourceResolver,
            mihonContentSourceResolver,
            aniyomiContentSourceResolver,
            ireaderContentSourceResolver,
        )
    }

    data class ResolutionStep(
        val resolver: String,
        val inputSource: String,
        val outputSource: String,
    )

    data class ResolutionResult(
        val resolvedSource: ContentSource,
        val steps: List<ResolutionStep>,
    )

    fun resolve(source: ContentSource): ContentSource {
        return resolveWithTrace(source).resolvedSource
    }

    fun resolveWithTrace(source: ContentSource): ResolutionResult {
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
}
