package org.skepsun.kototoro.extensions.repo

internal fun ExternalExtensionType.toInstalledPackageName(packageName: String): String {
    if (this != ExternalExtensionType.IREADER || !packageName.startsWith("ireader-")) {
        return packageName
    }
    val parts = packageName.split("-")
    if (parts.size < 3) {
        return packageName
    }
    val language = parts[1]
    val sourceName = parts.drop(2).joinToString("-")
    return "ireader.$sourceName.$language"
}
