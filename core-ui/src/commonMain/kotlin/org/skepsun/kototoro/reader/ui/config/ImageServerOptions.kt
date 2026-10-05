package org.skepsun.kototoro.reader.ui.config

/** The source's image servers, as the reader options offer them (
ull value = automatic). */
data class ImageServerOptions(
    val selectedValue: String?,
    val entries: List<ImageServerEntry>,
)

data class ImageServerEntry(
    val value: String?,
    val label: String?,
)
