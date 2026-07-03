package org.skepsun.kototoro.extensions.runtime

import android.content.pm.ApplicationInfo
import android.os.Bundle

object ExternalExtensionMetadataSupport {

	data class DeclaredSourceMetadata(
		val sourceClassName: String,
		val isNsfw: Boolean,
		val contentWarning: Int? = null,
		val libVersionOverride: Double? = null,
	)

	fun getMetaDataOrNull(appInfo: ApplicationInfo?): Bundle? = appInfo?.metaData

	fun hasDeclaredSource(
		metaData: Bundle?,
		sourceClassKey: String,
		sourceFactoryKey: String,
	): Boolean {
		return metaData?.containsKey(sourceClassKey) == true ||
			metaData?.containsKey(sourceFactoryKey) == true
	}

	fun getSourceClassNameOrNull(
		metaData: Bundle,
		sourceClassKey: String,
		sourceFactoryKey: String,
	): String? {
		return metaData.getString(sourceClassKey)
			?: metaData.getString(sourceFactoryKey)
	}

	fun isNsfw(metaData: Bundle, nsfwKey: String): Boolean {
		return metaData.getInt(nsfwKey, 0) == 1
	}

	fun getDeclaredSourceMetadataOrNull(
		metaData: Bundle,
		sourceClassKey: String,
		sourceFactoryKey: String,
		nsfwKey: String,
		contentWarningKey: String = "tachiyomix.contentWarning",
		libVersionKey: String = "tachiyomix.extensionLib",
	): DeclaredSourceMetadata? {
		val sourceClassName = getSourceClassNameOrNull(
			metaData = metaData,
			sourceClassKey = sourceClassKey,
			sourceFactoryKey = sourceFactoryKey,
		) ?: return null
		
		val contentWarningVal = if (metaData.containsKey(contentWarningKey)) {
			metaData.getInt(contentWarningKey)
		} else {
			null
		}
		
		val isNsfw = if (contentWarningVal != null) {
			contentWarningVal == 2
		} else {
			metaData.getInt(nsfwKey, 0) == 1
		}
		
		val libVersionOverride = if (metaData.containsKey(libVersionKey)) {
			try {
				val rawVal = metaData.get(libVersionKey)
				when (rawVal) {
					is Number -> rawVal.toDouble()
					is String -> rawVal.toDoubleOrNull()
					else -> null
				}
			} catch (e: Exception) {
				null
			}
		} else {
			null
		}
		
		return DeclaredSourceMetadata(
			sourceClassName = sourceClassName,
			isNsfw = isNsfw,
			contentWarning = contentWarningVal,
			libVersionOverride = libVersionOverride,
		)
	}
}
