package org.skepsun.kototoro.local.data

import android.content.Context
import android.net.Uri
import androidx.core.net.toUri
import com.hippo.unifile.UniFile
import java.io.File

class LocalStorageRoot private constructor(
	val uri: Uri,
	val file: UniFile,
) {

	val key: String
		get() = uri.toString()

	val displayPath: String
		get() = file.filePath ?: key

	val name: String
		get() = file.name ?: displayPath.substringAfterLast('/')

	val rawFile: File?
		get() = file.filePath?.takeIf { uri.scheme == "file" }?.let(::File)

	fun isReadable(): Boolean = file.exists() && file.isDirectory && file.canRead()

	fun isWriteable(): Boolean = isReadable() && file.canWrite()

	override fun equals(other: Any?): Boolean = other is LocalStorageRoot && other.key == key

	override fun hashCode(): Int = key.hashCode()

	override fun toString(): String = displayPath

	companion object {

		fun fromFile(file: File): LocalStorageRoot {
			val uri = file.toUri()
			return LocalStorageRoot(uri, checkNotNull(UniFile.fromFile(file)))
		}

		fun fromUri(context: Context, uri: Uri): LocalStorageRoot? {
			val file = UniFile.fromUri(context, uri) ?: return null
			return LocalStorageRoot(file.uri, file)
		}

		fun fromStoredValue(context: Context, value: String): LocalStorageRoot? {
			val uri = value.toUri().takeIf { it.scheme != null } ?: File(value).toUri()
			return fromUri(context, uri)
		}
	}
}
