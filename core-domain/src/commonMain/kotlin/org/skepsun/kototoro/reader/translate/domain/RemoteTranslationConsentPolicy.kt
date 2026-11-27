package org.skepsun.kototoro.reader.translate.domain

enum class RemoteTranslationBackend {
    LOCAL_OFFLINE,
    REMOTE_TEXT_API,
    REMOTE_E2E_VISION_API,
}

enum class RemoteTranslationPayloadKind {
    OCR_TEXT_AND_GLOSSARY,
    NOVEL_TEXT_AND_GLOSSARY,
    FULL_PAGE_IMAGE_AND_GLOSSARY,
}

enum class RemoteTranslationUsageMode {
    API_ONLY,
    LOCAL_FIRST,
    END_TO_END_API,
}

data class RemoteTranslationConsentInput(
    val backend: RemoteTranslationBackend,
    val providerId: String,
    val providerDisplayName: String,
    val endpointUrl: String,
    val payloadKind: RemoteTranslationPayloadKind,
    val consentedEndpointKeys: Set<String>,
    val usageMode: RemoteTranslationUsageMode = when (backend) {
        RemoteTranslationBackend.REMOTE_E2E_VISION_API -> RemoteTranslationUsageMode.END_TO_END_API
        else -> RemoteTranslationUsageMode.API_ONLY
    },
)

data class RemoteTranslationConsentRequest(
    val consentKey: String,
    val providerId: String,
    val providerDisplayName: String,
    val normalizedHost: String,
    val payloadKind: RemoteTranslationPayloadKind,
    val usageMode: RemoteTranslationUsageMode,
)

sealed interface RemoteTranslationConsentDecision {
    data object Allowed : RemoteTranslationConsentDecision

    data class RequiresConsent(
        val request: RemoteTranslationConsentRequest,
    ) : RemoteTranslationConsentDecision
}

/**
 * Pure cross-platform policy deciding whether sending translation payloads to a remote endpoint
 * requires explicit first-send user consent.
 */
object RemoteTranslationConsentPolicy {

    const val DEFAULT_PROVIDER_ID = "CUSTOM"
    private const val KEY_SEPARATOR = '|'

    fun evaluate(input: RemoteTranslationConsentInput): RemoteTranslationConsentDecision {
        if (input.backend == RemoteTranslationBackend.LOCAL_OFFLINE) {
            return RemoteTranslationConsentDecision.Allowed
        }
        val providerId = normalizeProviderId(input.providerId)
        val normalizedHost = extractNormalizedHost(input.endpointUrl)
        val consentKey = buildConsentKey(providerId, normalizedHost)
        if (consentKey != null && isConsented(input.consentedEndpointKeys, consentKey)) {
            return RemoteTranslationConsentDecision.Allowed
        }
        val displayName = input.providerDisplayName.trim().ifBlank { providerId }
        return RemoteTranslationConsentDecision.RequiresConsent(
            request = RemoteTranslationConsentRequest(
                consentKey = consentKey.orEmpty(),
                providerId = providerId,
                providerDisplayName = displayName,
                normalizedHost = normalizedHost.orEmpty(),
                payloadKind = input.payloadKind,
                usageMode = input.usageMode,
            ),
        )
    }

    fun normalizeProviderId(providerId: String?): String {
        return providerId
            ?.trim()
            ?.uppercase()
            ?.takeIf { it.isNotEmpty() && !it.contains(KEY_SEPARATOR) }
            ?: DEFAULT_PROVIDER_ID
    }

    fun buildConsentKey(providerId: String?, normalizedHost: String?): String? {
        val cleanHost = normalizedHost
            ?.trim()
            ?.lowercase()
            ?.takeIf { it.isNotEmpty() && !it.contains(KEY_SEPARATOR) && it.none(Char::isWhitespace) }
            ?: return null
        return "${normalizeProviderId(providerId)}$KEY_SEPARATOR$cleanHost"
    }

    fun recordConsent(existingKeys: Set<String>, consentKey: String?): Set<String> {
        val canonicalKey = canonicalizeConsentKey(consentKey) ?: return existingKeys
        return existingKeys + canonicalKey
    }

    /**
     * Safely parses and normalizes the host component from an endpoint URL without relying on
     * JVM-only URI APIs. Returns null for blank or malformed inputs.
     */
    fun extractNormalizedHost(endpointUrl: String?): String? {
        val trimmed = endpointUrl?.trim().orEmpty()
        if (trimmed.isEmpty() || trimmed.any { it <= ' ' }) {
            return null
        }

        val schemeDelimiterIndex = trimmed.indexOf("://")
        val afterScheme = when {
            schemeDelimiterIndex >= 0 -> {
                val scheme = trimmed.substring(0, schemeDelimiterIndex)
                if (!isValidScheme(scheme)) return null
                trimmed.substring(schemeDelimiterIndex + 3)
            }

            trimmed.startsWith("//") -> trimmed.substring(2)

            trimmed.startsWith("/") || trimmed.startsWith("?") || trimmed.startsWith("#") || trimmed.startsWith(":") -> {
                return null
            }

            else -> trimmed
        }

        if (afterScheme.isEmpty()) return null

        val authorityEnd = afterScheme.indexOfAny(charArrayOf('/', '?', '#')).let {
            if (it >= 0) it else afterScheme.length
        }
        val authority = afterScheme.substring(0, authorityEnd)
        if (authority.isEmpty()) return null

        val hasExplicitAuthority = schemeDelimiterIndex >= 0 || trimmed.startsWith("//")
        val hostPort = if (authority.contains('@')) {
            if (!hasExplicitAuthority) return null
            authority.substringAfterLast('@')
        } else {
            authority
        }
        if (hostPort.isEmpty()) return null

        return if (hostPort.startsWith("[")) {
            parseBracketedIpv6Host(hostPort)
        } else {
            parseHostnameOrIpv4(hostPort)
        }
    }

    private fun isConsented(consentedEndpointKeys: Set<String>, canonicalConsentKey: String): Boolean {
        return consentedEndpointKeys.any { canonicalizeConsentKey(it) == canonicalConsentKey }
    }

    private fun canonicalizeConsentKey(rawKey: String?): String? {
        val trimmed = rawKey?.trim().orEmpty()
        if (trimmed.isEmpty()) return null
        val separatorIndex = trimmed.indexOf(KEY_SEPARATOR)
        if (separatorIndex <= 0 || separatorIndex != trimmed.lastIndexOf(KEY_SEPARATOR)) {
            return null
        }
        val providerPart = trimmed.substring(0, separatorIndex)
        val hostPart = trimmed.substring(separatorIndex + 1)
        return buildConsentKey(providerPart, hostPart)
    }

    private fun isValidScheme(scheme: String): Boolean {
        if (scheme.isEmpty()) return false
        val first = scheme[0]
        if (!first.isAsciiLetter()) return false
        return scheme.all { it.isAsciiLetter() || it.isAsciiDigit() || it == '+' || it == '-' || it == '.' }
    }

    private fun parseBracketedIpv6Host(hostPort: String): String? {
        val closingIndex = hostPort.indexOf(']')
        if (closingIndex <= 1) return null
        val inside = hostPort.substring(1, closingIndex)
        if (!inside.contains(':') || !inside.all { it.isHexDigit() || it == ':' || it == '.' || it == '%' }) {
            return null
        }
        val remainder = hostPort.substring(closingIndex + 1)
        if (remainder.isNotEmpty()) {
            if (!remainder.startsWith(':') || !isValidPort(remainder.substring(1))) {
                return null
            }
        }
        return "[${inside.lowercase()}]"
    }

    private fun parseHostnameOrIpv4(hostPort: String): String? {
        val firstColon = hostPort.indexOf(':')
        val hostPart = if (firstColon >= 0) {
            if (hostPort.indexOf(':', firstColon + 1) >= 0) {
                return null
            }
            val portPart = hostPort.substring(firstColon + 1)
            if (!isValidPort(portPart)) {
                return null
            }
            hostPort.substring(0, firstColon)
        } else {
            hostPort
        }

        val normalized = hostPart.removeSuffix(".").lowercase()
        if (normalized.isEmpty()) return null
        if (normalized.startsWith('.') || normalized.endsWith('.') || normalized.contains("..")) {
            return null
        }
        val labels = normalized.split('.')
        val validLabels = labels.all { label ->
            label.isNotEmpty() &&
                !label.startsWith('-') &&
                !label.endsWith('-') &&
                label.all { it.isAsciiLetter() || it.isAsciiDigit() || it == '-' }
        }
        return normalized.takeIf { validLabels }
    }

    private fun isValidPort(port: String): Boolean {
        if (port.isEmpty() || !port.all { it.isAsciiDigit() }) return false
        val number = port.toIntOrNull() ?: return false
        return number in 0..65535
    }

    private fun Char.isAsciiLetter(): Boolean = this in 'a'..'z' || this in 'A'..'Z'

    private fun Char.isAsciiDigit(): Boolean = this in '0'..'9'

    private fun Char.isHexDigit(): Boolean = isAsciiDigit() || this in 'a'..'f' || this in 'A'..'F'
}
