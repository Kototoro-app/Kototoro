package org.skepsun.kototoro.extensions.install

enum class ExtensionInstallPolicy {
	ASK_EVERY_TIME,
	INSTALL_ONLY,
	INSTALL_AND_ENABLE,
}

internal fun decodeExtensionInstallPolicies(entries: Set<String>?): Map<String, ExtensionInstallPolicy> {
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

internal fun encodeExtensionInstallPolicies(
	policies: Map<String, ExtensionInstallPolicy>,
): Set<String> {
	return policies.mapTo(linkedSetOf()) { (type, policy) -> "$type=${policy.name}" }
}
