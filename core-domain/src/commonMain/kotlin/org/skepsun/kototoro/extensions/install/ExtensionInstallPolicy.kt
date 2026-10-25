package org.skepsun.kototoro.extensions.install

/**
 * Android's `extension_install_behavior`: what installing an extension package does to the sources it brings.
 *
 * Android asks per source type (its `AppSettings.getExtensionInstallPolicy(type)`), stores the answers as a
 * `Set<String>` of `"<type>=<POLICY>"` under `extension_install_policies`, and leaves the default out of the set, so
 * a fresh install keeps asking. Windows reads the same names and the same encoding, which is why the value lives in
 * this shared module instead of either host.
 */
enum class ExtensionInstallPolicy {
    ASK_EVERY_TIME,
    INSTALL_ONLY,
    INSTALL_AND_ENABLE,
}

/** The stored entries as a map; unknown policies and entries without a separator are dropped, never guessed. */
fun decodeExtensionInstallPolicies(entries: Set<String>?): Map<String, ExtensionInstallPolicy> {
    return entries
        .orEmpty()
        .mapNotNull { entry ->
            val (type, policyName) = entry.split('=', limit = 2).takeIf { it.size == 2 } ?: return@mapNotNull null
            val policy = runCatching { ExtensionInstallPolicy.valueOf(policyName) }.getOrNull()
                ?: return@mapNotNull null
            type to policy
        }
        .toMap()
}

/** The map as the entries to store; the caller decides whether the default belongs in it. */
fun encodeExtensionInstallPolicies(
    policies: Map<String, ExtensionInstallPolicy>,
): Set<String> {
    return policies.mapTo(linkedSetOf()) { (type, policy) -> "$type=${policy.name}" }
}
