package org.skepsun.kototoro.extensions.repo

internal object ExtensionFingerprintTrust {

    fun isTrusted(expectedFingerprint: String, actualFingerprints: Set<String>): Boolean {
        if (expectedFingerprint.isBlank()) return true
        val normalizedExpected = expectedFingerprint.normalizeExtensionFingerprint()
        if (normalizedExpected.isEmpty()) return true
        return actualFingerprints.any { it.normalizeExtensionFingerprint() == normalizedExpected }
    }
}

internal fun String.normalizeExtensionFingerprint(): String {
    return lowercase().replace(":", "").replace(" ", "")
}
