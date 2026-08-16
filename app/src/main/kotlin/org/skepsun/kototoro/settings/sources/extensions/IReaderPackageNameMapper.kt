package org.skepsun.kototoro.settings.sources.extensions

import org.skepsun.kototoro.extensions.repo.ExternalExtensionType
import org.skepsun.kototoro.extensions.repo.toInstalledPackageName

internal fun ExternalExtensionType.normalizePackageNameForMatching(packageName: String): String {
	return toInstalledPackageName(packageName)
}

internal fun String.toInstalledIReaderPackageName(): String {
	return ExternalExtensionType.IREADER.toInstalledPackageName(this)
}
