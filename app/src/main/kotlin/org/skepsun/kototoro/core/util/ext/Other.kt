package org.skepsun.kototoro.core.util.ext

import org.skepsun.kototoro.core.io.NullOutputStream
import java.io.ObjectOutputStream

@Suppress("UNCHECKED_CAST")
fun <T> Class<T>.castOrNull(obj: Any?): T? {
	if (obj == null || !isInstance(obj)) {
		return null
	}
	return obj as T
}

fun Any.isSerializable() = runCatching {
	val oos = ObjectOutputStream(NullOutputStream())
	oos.writeObject(this)
	oos.flush()
}.isSuccess

fun Throwable.toSerializableThrowable(): Throwable {
	if (isSerializable()) return this

	return RuntimeException(
		buildString {
			append(this@toSerializableThrowable.javaClass.name)
			this@toSerializableThrowable.message?.let {
				append(": ")
				append(it)
			}
		},
	).also { serializableError ->
		serializableError.stackTrace = stackTrace
	}
}
