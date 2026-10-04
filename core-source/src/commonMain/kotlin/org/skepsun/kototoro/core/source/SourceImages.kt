package org.skepsun.kototoro.core.source

import kotlinx.serialization.Serializable

/** Original native Page fields; hashed page IDs are not native indexes. Image fragments remain opaque. */
@Serializable
data class SourcePageRequestContext(val index: Int, val url: String, val imageUrl: String?, val uri: String? = null) {
    init { require(index >= 0) }
}

/** The platform provides a private image directory shared by the JVM and native consumer. */
@Serializable
data class SourceImageArtifact(
    val source: SourceRef,
    @Serializable(with = SourceLongSerializer::class) val pageId: Long,
    val relativePath: String,
    val sha256: String,
    @Serializable(with = SourceLongSerializer::class) val byteSize: Long,
    val contentType: String,
) {
    init { validateImageFile(relativePath, sha256, byteSize, contentType) }
}

/** Cover identity is content identity, independent of native chapter Page indexes. */
@Serializable
data class SourceCoverArtifact(
    val source: SourceRef,
    @Serializable(with = SourceLongSerializer::class) val contentId: Long,
    val relativePath: String,
    val sha256: String,
    @Serializable(with = SourceLongSerializer::class) val byteSize: Long,
    val contentType: String,
) {
    init { validateImageFile(relativePath, sha256, byteSize, contentType) }
}

private fun validateImageFile(path: String, hash: String, size: Long, type: String) {
    require(hash.matches(Regex("[0-9a-f]{64}")))
    require(path == "$hash.img")
    require(size > 0)
    require(type.startsWith("image/"))
}
