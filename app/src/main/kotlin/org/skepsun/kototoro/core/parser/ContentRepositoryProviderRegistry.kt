package org.skepsun.kototoro.core.parser

import org.skepsun.kototoro.parsers.model.ContentSource
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ContentRepositoryProviderRegistry @Inject constructor(
    private val builtinContentRepositoryProvider: BuiltinContentRepositoryProvider,
    private val parserContentRepositoryProvider: ParserContentRepositoryProvider,
    private val kotatsuContentRepositoryProvider: KotatsuContentRepositoryProvider,
    private val testContentRepositoryProvider: TestContentRepositoryProvider,
    private val externalContentRepositoryProvider: ExternalContentRepositoryProvider,
    private val cloudstreamContentRepositoryProvider: org.skepsun.kototoro.cloudstream.runtime.CloudstreamContentRepositoryProvider,
    private val mihonContentRepositoryProvider: MihonContentRepositoryProvider,
    private val aniyomiContentRepositoryProvider: AniyomiContentRepositoryProvider,
    private val ireaderContentRepositoryProvider: IReaderContentRepositoryProvider,
    private val tsundokuContentRepositoryProvider: TsundokuContentRepositoryProvider,
    private val jsonContentRepositoryProvider: JsonContentRepositoryProvider,
    private val trackingContentRepositoryProvider: org.skepsun.kototoro.tracking.discovery.data.TrackingContentRepositoryProvider,
) {

    private val providers: List<ContentRepositoryProvider> by lazy(LazyThreadSafetyMode.NONE) {
        listOf(
            builtinContentRepositoryProvider,
            parserContentRepositoryProvider,
            kotatsuContentRepositoryProvider,
            testContentRepositoryProvider,
            externalContentRepositoryProvider,
            cloudstreamContentRepositoryProvider,
            mihonContentRepositoryProvider,
            aniyomiContentRepositoryProvider,
            ireaderContentRepositoryProvider,
            tsundokuContentRepositoryProvider,
            jsonContentRepositoryProvider,
            trackingContentRepositoryProvider,
        )
    }

    data class SelectionResult(
        val repository: ContentRepository?,
        val candidateProviders: List<String>,
        val attemptedProviders: List<String>,
        val selectedProvider: String?,
    )

    fun select(source: ContentSource): SelectionResult {
        val supportedProviders = providers.filter { provider -> provider.supports(source) }
        android.util.Log.d(
            "ContentRepoFactory",
            "stage=provider_candidates source=${source.name} candidates=${supportedProviders.joinToString { it::class.simpleName ?: it::class.java.simpleName }}",
        )
        val attemptedProviders = ArrayList<String>(supportedProviders.size)
        for (provider in supportedProviders) {
            val providerName = provider::class.simpleName ?: provider::class.java.simpleName
            attemptedProviders += providerName
            android.util.Log.d("ContentRepoFactory", "stage=provider_attempt source=${source.name} provider=$providerName")
            val repository = provider.create(source)
            if (repository != null) {
                return SelectionResult(
                    repository = repository,
                    candidateProviders = supportedProviders.map { it::class.simpleName ?: it::class.java.simpleName },
                    attemptedProviders = attemptedProviders,
                    selectedProvider = providerName,
                )
            }
        }
        return SelectionResult(
            repository = null,
            candidateProviders = supportedProviders.map { it::class.simpleName ?: it::class.java.simpleName },
            attemptedProviders = attemptedProviders,
            selectedProvider = null,
        )
    }
}
