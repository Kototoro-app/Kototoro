package org.skepsun.kototoro.settings.sources.extensions

internal fun String.formatExtensionFingerprint(): String {
    return uppercase()
        .chunked(8)
        .chunked(4)
        .joinToString("\n") { line -> line.joinToString(" ") }
}
