@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package org.skepsun.kototoro.extensions.repo

import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoNumber

/** Aniyomi reserves Extension field 8 for isTorrent and publishes sources at field 9. */
@Serializable
internal data class AniyomiExtensionStoreIndex(
    @ProtoNumber(1) val name: String = "",
    @ProtoNumber(2) val badgeLabel: String = "",
    @ProtoNumber(3) val signingKey: String = "",
    @ProtoNumber(4) val contact: ExtensionStoreIndex.Contact = ExtensionStoreIndex.Contact(""),
    @ProtoNumber(101) val extensionList: ExtensionList? = null,
    @ProtoNumber(102) val extensionListUrl: String? = null,
) {
    fun toStoreIndex() = ExtensionStoreIndex(
        name = name,
        badgeLabel = badgeLabel,
        signingKey = signingKey,
        contact = contact,
        extensionList = extensionList?.toStoreList(),
        extensionListUrl = extensionListUrl,
    )

    @Serializable
    data class ExtensionList(
        @ProtoNumber(1) val extensions: List<Extension> = emptyList(),
    ) {
        fun toStoreList() = ExtensionStoreIndex.ExtensionList(extensions.map { it.toStoreExtension() })
    }

    @Serializable
    data class Extension(
        @ProtoNumber(1) val name: String = "",
        @ProtoNumber(2) val packageName: String = "",
        @ProtoNumber(3) val resources: ExtensionStoreIndex.Resources = ExtensionStoreIndex.Resources("", ""),
        @ProtoNumber(4) val extensionLib: String = "",
        @ProtoNumber(5) val versionCode: Long = 0,
        @ProtoNumber(6) val versionName: String = "",
        @ProtoNumber(7) val contentWarning: ExtensionStoreIndex.ContentWarning =
            ExtensionStoreIndex.ContentWarning.UNSPECIFIED,
        @ProtoNumber(8) val isTorrent: Boolean = false,
        @ProtoNumber(9) val sources: List<ExtensionStoreIndex.Source> = emptyList(),
    ) {
        fun toStoreExtension() = ExtensionStoreIndex.Extension(
            name = name,
            packageName = packageName,
            resources = resources,
            extensionLib = extensionLib,
            versionCode = versionCode,
            versionName = versionName,
            contentWarning = contentWarning,
            sources = sources,
        )
    }
}
