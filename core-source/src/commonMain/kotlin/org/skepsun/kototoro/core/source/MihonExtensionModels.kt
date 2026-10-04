package org.skepsun.kototoro.core.source

import kotlinx.serialization.Serializable

/** Metadata read from a repository-provided JVM JAR, independently of Android PackageManager. */
@Serializable
data class MihonJarMetadata(
    val packageName: String,
    val displayName: String,
    val versionCode: Long,
    val versionName: String,
    val extensionLib: String,
    val entryClasses: List<String>,
    val isNsfw: Boolean,
    val sha256: String,
    val maximumClassVersion: Int,
    /** The Tachiyomi-ABI family the manifest declares; novels run on the same ABI as manga. */
    val ecosystem: SourceEcosystem = SourceEcosystem.MIHON,
)

/** Expected repository identity is mandatory before executing an extension's constructor. */
@Serializable
data class MihonJarIdentity(val packageName: String, val versionCode: Long, val sha256: String)

@Serializable
data class MihonSourceDescriptor(
    val source: SourceRef,
    @Serializable(with = SourceLongSerializer::class) val sourceId: Long,
    val displayName: String,
    val supportsLatest: Boolean,
)

@Serializable
data class LoadedMihonExtension(val metadata: MihonJarMetadata, val sources: List<MihonSourceDescriptor>)
