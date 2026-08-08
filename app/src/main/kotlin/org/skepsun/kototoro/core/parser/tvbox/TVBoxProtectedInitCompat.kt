package org.skepsun.kototoro.core.parser.tvbox

import android.content.Context
import java.lang.reflect.Field
import java.lang.reflect.Modifier

internal object TVBoxProtectedInitCompat {

	fun seedContext(init: Any, context: Context) {
		val field = findContextField(init.javaClass, context.javaClass)
			?: throw NoSuchFieldException("No Context-compatible field in ${init.javaClass.name}")
		field.isAccessible = true
		field.set(init, context)
	}

	fun loadNativeLibraries(init: Any) {
		val method = init.javaClass.methods.firstOrNull { candidate ->
			candidate.name == "exeLibStub" && candidate.parameterCount == 0
		} ?: return
		method.invoke(init)
	}

	internal fun findContextField(type: Class<*>, contextType: Class<*>): Field? {
		return collectFields(type)
			.asSequence()
			.filterNot { Modifier.isStatic(it.modifiers) }
			.filter { field ->
				Context::class.java.isAssignableFrom(field.type) && field.type.isAssignableFrom(contextType)
			}
			.sortedWith(
				compareByDescending<Field> { it.name == LEGACY_CONTEXT_FIELD }
			)
			.firstOrNull()
	}

	private fun collectFields(type: Class<*>): List<Field> = buildList {
		var current: Class<*>? = type
		while (current != null && current != Any::class.java) {
			addAll(current.declaredFields)
			current = current.superclass
		}
	}

	private const val LEGACY_CONTEXT_FIELD = "c"
}
