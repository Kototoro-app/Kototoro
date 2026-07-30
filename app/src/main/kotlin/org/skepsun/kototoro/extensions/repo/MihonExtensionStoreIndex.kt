package org.skepsun.kototoro.extensions.repo

import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoNumber

@Serializable
internal data class MihonExtensionStoreIndex(
	@ProtoNumber(1)
	val name: String,
	@ProtoNumber(2)
	val badgeLabel: String,
	@ProtoNumber(3)
	val signingKey: String,
	@ProtoNumber(4)
	val contact: Contact,
	@ProtoNumber(101)
	val extensionList: ExtensionList? = null,
	@ProtoNumber(102)
	val extensionListUrl: String? = null,
) {

	@Serializable
	data class Contact(
		@ProtoNumber(1)
		val website: String,
		@ProtoNumber(2)
		val discord: String? = null,
	)

	@Serializable
	data class ExtensionList(
		@ProtoNumber(1)
		val extensions: List<Extension>,
	)

	@Serializable
	data class Extension(
		@ProtoNumber(1)
		val name: String,
		@ProtoNumber(2)
		val packageName: String,
		@ProtoNumber(3)
		val resources: Resources,
		@ProtoNumber(4)
		val extensionLib: String,
		@ProtoNumber(5)
		val versionCode: Long,
		@ProtoNumber(6)
		val versionName: String,
		@ProtoNumber(7)
		val contentWarning: ContentWarning,
		@ProtoNumber(8)
		val sources: List<Source>,
	)

	@Serializable
	data class Resources(
		@ProtoNumber(1)
		val apkUrl: String,
		@ProtoNumber(2)
		val iconUrl: String,
		@ProtoNumber(501)
		val jarUrl: String = "",
	)

	@Serializable
	data class Source(
		@ProtoNumber(1)
		val id: Long,
		@ProtoNumber(2)
		val name: String,
		@ProtoNumber(3)
		val language: String,
		@ProtoNumber(4)
		val homeUrl: String = "",
		@ProtoNumber(5)
		val mirrorUrls: List<String> = emptyList(),
		@ProtoNumber(7)
		val message: String? = null,
	)

	@Serializable
	enum class ContentWarning {
		@ProtoNumber(0)
		UNSPECIFIED,

		@ProtoNumber(1)
		SAFE,

		@ProtoNumber(2)
		MIXED,

		@ProtoNumber(3)
		NSFW,
	}
}
