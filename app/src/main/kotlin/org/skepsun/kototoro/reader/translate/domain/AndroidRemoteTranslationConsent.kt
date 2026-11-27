package org.skepsun.kototoro.reader.translate.domain

import org.skepsun.kototoro.core.prefs.AppSettings
import org.skepsun.kototoro.core.prefs.ReaderTranslationMode
import org.skepsun.kototoro.core.prefs.ReaderTranslationPipelineMode

internal data class PendingRemoteTranslationConsent(
    val request: RemoteTranslationConsentRequest,
    val onConfirm: () -> Unit,
)

internal fun AppSettings.evaluateRemoteTranslationConsent(
    isNovel: Boolean,
    customProviderLabel: String,
): RemoteTranslationConsentDecision = evaluateAndroidRemoteTranslationConsent(
    pipelineMode = readerTranslationPipelineMode,
    translationMode = readerTranslationMode,
    apiProviderPreset = readerTranslationApiProviderPreset,
    apiEndpoint = readerTranslationApiEndpoint,
    e2eProviderPreset = readerE2eApiProviderPreset,
    e2eEndpoint = readerE2eApiEndpoint,
    isNovel = isNovel,
    consentedEndpointKeys = remoteTranslationConsentedEndpoints,
    customProviderLabel = customProviderLabel,
)

internal fun evaluateAndroidRemoteTranslationConsent(
    pipelineMode: ReaderTranslationPipelineMode,
    translationMode: ReaderTranslationMode,
    apiProviderPreset: String,
    apiEndpoint: String,
    e2eProviderPreset: String,
    e2eEndpoint: String,
    isNovel: Boolean,
    consentedEndpointKeys: Set<String>,
    customProviderLabel: String,
): RemoteTranslationConsentDecision {
    if (!isNovel && pipelineMode == ReaderTranslationPipelineMode.END_TO_END_API) {
        val normalizedPreset = RemoteTranslationConsentPolicy.normalizeProviderId(e2eProviderPreset)
        val resolvedEndpoint = e2eEndpoint.trim().ifBlank {
            TranslationApiProviderCatalog.find(normalizedPreset)?.chatEndpoint.orEmpty()
        }
        return RemoteTranslationConsentPolicy.evaluate(
            RemoteTranslationConsentInput(
                backend = RemoteTranslationBackend.REMOTE_E2E_VISION_API,
                providerId = normalizedPreset,
                providerDisplayName = resolveE2eProviderDisplayName(normalizedPreset, customProviderLabel),
                endpointUrl = resolvedEndpoint,
                payloadKind = RemoteTranslationPayloadKind.FULL_PAGE_IMAGE_AND_GLOSSARY,
                consentedEndpointKeys = consentedEndpointKeys,
                usageMode = RemoteTranslationUsageMode.END_TO_END_API,
            ),
        )
    }

    if (translationMode == ReaderTranslationMode.LOCAL_ONLY) {
        return RemoteTranslationConsentPolicy.evaluate(
            RemoteTranslationConsentInput(
                backend = RemoteTranslationBackend.LOCAL_OFFLINE,
                providerId = apiProviderPreset,
                providerDisplayName = customProviderLabel,
                endpointUrl = apiEndpoint,
                payloadKind = if (isNovel) {
                    RemoteTranslationPayloadKind.NOVEL_TEXT_AND_GLOSSARY
                } else {
                    RemoteTranslationPayloadKind.OCR_TEXT_AND_GLOSSARY
                },
                consentedEndpointKeys = consentedEndpointKeys,
            ),
        )
    }

    val normalizedPreset = RemoteTranslationConsentPolicy.normalizeProviderId(apiProviderPreset)
    val resolvedEndpoint = TranslationApiProviderCatalog.resolveChatEndpoint(
        providerId = normalizedPreset,
        customEndpoint = apiEndpoint,
    )
    val providerDisplayName = TranslationApiProviderCatalog.find(normalizedPreset)?.name
        ?: customProviderLabel.trim().ifBlank { normalizedPreset }
    val usageMode = when (translationMode) {
        ReaderTranslationMode.LOCAL_FIRST -> RemoteTranslationUsageMode.LOCAL_FIRST
        else -> RemoteTranslationUsageMode.API_ONLY
    }
    return RemoteTranslationConsentPolicy.evaluate(
        RemoteTranslationConsentInput(
            backend = RemoteTranslationBackend.REMOTE_TEXT_API,
            providerId = normalizedPreset,
            providerDisplayName = providerDisplayName,
            endpointUrl = resolvedEndpoint,
            payloadKind = if (isNovel) {
                RemoteTranslationPayloadKind.NOVEL_TEXT_AND_GLOSSARY
            } else {
                RemoteTranslationPayloadKind.OCR_TEXT_AND_GLOSSARY
            },
            consentedEndpointKeys = consentedEndpointKeys,
            usageMode = usageMode,
        ),
    )
}

private fun resolveE2eProviderDisplayName(providerId: String, customProviderLabel: String): String {
    TranslationApiProviderCatalog.find(providerId)?.let { return it.name }
    return when (providerId) {
        "OLLAMA" -> "Ollama"
        RemoteTranslationConsentPolicy.DEFAULT_PROVIDER_ID -> customProviderLabel.trim().ifBlank { providerId }
        else -> providerId
    }
}
