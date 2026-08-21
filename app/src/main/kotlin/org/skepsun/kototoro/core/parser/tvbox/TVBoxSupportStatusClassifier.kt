package org.skepsun.kototoro.core.parser.tvbox

import org.skepsun.kototoro.core.model.jsonsource.TVBoxStoredConfig

internal enum class TVBoxSupportStatus {
    DIRECT,
    PARTIAL_RUNTIME,
    QUICKJS_PARTIAL,
    BRIDGEABLE,
    SPIDER_BRIDGE,
    ORDINARY_JAR,
    GUARD_NATIVE,
}

internal object TVBoxSupportStatusClassifier {

    fun classify(config: TVBoxStoredConfig, candidates: List<String>): TVBoxSupportStatus {
        val hasPlayableCandidate = candidates.any(::looksLikePlayableCandidate)
        val hasCmsCandidate = candidates.any(::looksLikeCmsCandidate)
        val hasSpiderArtifacts = hasSpiderArtifacts(config)
        if (hasGuardNativeSignals(config)) {
            return TVBoxSupportStatus.GUARD_NATIVE
        }
        if (config.site.type == 4) {
            return TVBoxSupportStatus.QUICKJS_PARTIAL
        }
        if (hasOrdinaryJarArtifacts(config)) {
            return TVBoxSupportStatus.ORDINARY_JAR
        }
        if (hasPlayableCandidate || hasCmsCandidate) {
            return if (hasSpiderArtifacts) {
                TVBoxSupportStatus.BRIDGEABLE
            } else {
                TVBoxSupportStatus.PARTIAL_RUNTIME
            }
        }
        return when {
            hasSpiderArtifacts -> TVBoxSupportStatus.SPIDER_BRIDGE
            else -> TVBoxSupportStatus.DIRECT
        }
    }

    fun hasSpiderArtifacts(config: TVBoxStoredConfig): Boolean {
        return !config.root.spider.isNullOrBlank() ||
            !config.site.jar.isNullOrBlank() ||
            config.site.type == 3 ||
            config.site.type == 4 ||
            config.site.api.startsWith("csp_", ignoreCase = true)
    }

    private fun hasGuardNativeSignals(config: TVBoxStoredConfig): Boolean {
        val signalText = listOfNotNull(
            config.root.spider,
            config.site.api,
            config.site.jar,
            config.site.name,
            config.site.ext?.toString(),
        ).joinToString(separator = "\n")
        return TVBOX_GUARD_NATIVE_PATTERN.containsMatchIn(signalText)
    }

    private fun hasOrdinaryJarArtifacts(config: TVBoxStoredConfig): Boolean {
        return config.site.type == 3 ||
            config.site.api.startsWith("csp_", ignoreCase = true) ||
            !config.root.spider.isNullOrBlank() ||
            !config.site.jar.isNullOrBlank()
    }

    private fun looksLikePlayableCandidate(url: String): Boolean {
        val normalized = url.lowercase()
        return normalized.contains(".m3u8") ||
            normalized.contains(".mp4") ||
            normalized.contains(".flv") ||
            normalized.contains(".mpd") ||
            normalized.contains(".mkv") ||
            normalized.contains(".webm") ||
            normalized.contains(".avi") ||
            normalized.contains(".mov") ||
            normalized.endsWith(".m3u")
    }

    private fun looksLikeCmsCandidate(url: String): Boolean {
        val normalized = url.lowercase()
        return normalized.contains("provide/vod") ||
            normalized.contains("api.php") ||
            normalized.contains(".php")
    }

    private val TVBOX_GUARD_NATIVE_PATTERN = Regex(
        pattern = "(guard|dexnative|basespiderguard|wex)",
        options = setOf(RegexOption.IGNORE_CASE),
    )
}
