package org.skepsun.kototoro.core.parser.legado

import java.util.Locale

object LegadoReflectiveAccess {

    fun readProperty(target: Any?, name: String): Any? {
        if (target == null || name.isBlank()) return null

        if (target is Map<*, *>) {
            return target[name]
        }

        runCatching {
            target.javaClass.fields.firstOrNull { it.name == name }?.let { field ->
                field.isAccessible = true
                return field.get(target)
            }
        }

        val suffix = name.replaceFirstChar { firstChar ->
            if (firstChar.isLowerCase()) {
                firstChar.titlecase(Locale.ROOT)
            } else {
                firstChar.toString()
            }
        }
        val methodNames = arrayOf(name, "get$suffix", "is$suffix")
        methodNames.forEach { methodName ->
            runCatching {
                target.javaClass.methods.firstOrNull { method ->
                    method.name == methodName && method.parameterCount == 0
                }?.let { method ->
                    method.isAccessible = true
                    return method.invoke(target)
                }
            }
        }

        return null
    }

    fun hasReadableProperty(target: Any?, name: String): Boolean {
        return readProperty(target, name) != null
    }
}
