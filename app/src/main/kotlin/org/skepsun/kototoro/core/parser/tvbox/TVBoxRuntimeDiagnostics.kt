package org.skepsun.kototoro.core.parser.tvbox

import android.util.Log
import java.lang.reflect.InvocationTargetException
import java.util.concurrent.TimeoutException

internal enum class TVBoxRuntimeFailureCategory(val id: String) {
    JSON_IMPORT("json_import"),
    MULTI_REPO("multi_repo"),
    DIRECT_MEDIA("direct_media"),
    CMS_FALLBACK("cms_fallback"),
    QUICKJS_MISSING_FEATURE("quickjs_missing_feature"),
    ORDINARY_JAR_MISSING_CLASS("ordinary_jar_missing_class"),
    ORDINARY_JAR_MISSING_METHOD("ordinary_jar_missing_method"),
    ORDINARY_JAR_PROXY("ordinary_jar_proxy"),
    GUARD_NATIVE("guard_native"),
    ORDINARY_JAR_RUNTIME("ordinary_jar_runtime"),
    UNKNOWN("unknown"),
}

internal object TVBoxRuntimeDiagnostics {

    fun logFailure(
        tag: String,
        sourceName: String,
        runtimeId: String?,
        action: String,
        category: TVBoxRuntimeFailureCategory,
        error: Throwable? = null,
        detail: String? = null,
    ) {
        val message = buildMessage(sourceName, runtimeId, action, category, detail, error)
        if (error == null) {
            Log.w(tag, message)
        } else {
            Log.w(tag, message, error)
        }
    }

    fun classifyQuickJs(error: Throwable?, detail: String? = null): TVBoxRuntimeFailureCategory {
        val text = listOfNotNull(detail, error.flattenMessages()).joinToString(" ").lowercase()
        return when {
            text.contains("//bb") ||
                text.contains("bytecode") ||
                text.contains("es module") ||
                text.contains("import is not supported") ||
                text.contains("cat.js") ||
                text.contains("js2proxy") ||
                text.contains("is missing") -> TVBoxRuntimeFailureCategory.QUICKJS_MISSING_FEATURE

            text.contains("proxy") -> TVBoxRuntimeFailureCategory.ORDINARY_JAR_PROXY
            else -> TVBoxRuntimeFailureCategory.QUICKJS_MISSING_FEATURE
        }
    }

    fun classifyJar(error: Throwable?, action: String? = null): TVBoxRuntimeFailureCategory {
        val root = error.rootCause()
        val text = listOfNotNull(action, error.flattenMessages(), root?.javaClass?.name).joinToString(" ").lowercase()
        return when {
            root is NoClassDefFoundError || root is ClassNotFoundException -> {
                if (text.looksLikeGuardNative()) {
                    TVBoxRuntimeFailureCategory.GUARD_NATIVE
                } else {
                    TVBoxRuntimeFailureCategory.ORDINARY_JAR_MISSING_CLASS
                }
            }
            root is NoSuchMethodError || root is NoSuchMethodException || text.contains("nosuchmethod") -> {
                TVBoxRuntimeFailureCategory.ORDINARY_JAR_MISSING_METHOD
            }
            root is UnsatisfiedLinkError ||
                text.contains("jni detected error") ||
                text.contains("sigabrt") ||
                text.contains("dexnative") ||
                text.looksLikeGuardNative() -> TVBoxRuntimeFailureCategory.GUARD_NATIVE

            text.contains("proxy") -> TVBoxRuntimeFailureCategory.ORDINARY_JAR_PROXY
            root is TimeoutException -> TVBoxRuntimeFailureCategory.ORDINARY_JAR_RUNTIME
            else -> TVBoxRuntimeFailureCategory.ORDINARY_JAR_RUNTIME
        }
    }

    fun classifyRepository(error: Throwable?, requiresSpiderRuntime: Boolean): TVBoxRuntimeFailureCategory {
        if (requiresSpiderRuntime) {
            return classifyJar(error)
        }
        val text = error.flattenMessages().lowercase()
        return when {
            text.contains("media") || text.contains("playback") -> TVBoxRuntimeFailureCategory.DIRECT_MEDIA
            else -> TVBoxRuntimeFailureCategory.CMS_FALLBACK
        }
    }

    private fun buildMessage(
        sourceName: String,
        runtimeId: String?,
        action: String,
        category: TVBoxRuntimeFailureCategory,
        detail: String?,
        error: Throwable?,
    ): String = buildString {
        append("TVBox runtime failure")
        append(": category=")
        append(category.id)
        append(" source=")
        append(sourceName)
        runtimeId?.let {
            append(" runtime=")
            append(it)
        }
        append(" action=")
        append(action)
        detail?.takeIf { it.isNotBlank() }?.let {
            append(" detail=")
            append(it)
        }
        error?.let {
            append(" error=")
            append(it.javaClass.name)
            it.message?.takeIf(String::isNotBlank)?.let { message ->
                append(": ")
                append(message)
            }
        }
    }

    private fun Throwable?.flattenMessages(): String {
        if (this == null) {
            return ""
        }
        val parts = ArrayList<String>()
        var current: Throwable? = this
        var depth = 0
        while (current != null && depth < 8) {
            parts += current.javaClass.name
            current.message?.let(parts::add)
            current = if (current is InvocationTargetException) {
                current.targetException ?: current.cause
            } else {
                current.cause
            }
            depth++
        }
        return parts.joinToString(" ")
    }

    private fun Throwable?.rootCause(): Throwable? {
        var current = this ?: return null
        var depth = 0
        while (depth < 8) {
            val next = if (current is InvocationTargetException) {
                current.targetException ?: current.cause
            } else {
                current.cause
            } ?: return current
            if (next === current) {
                return current
            }
            current = next
            depth++
        }
        return current
    }

    private fun String.looksLikeGuardNative(): Boolean {
        return contains("guard") ||
            contains("dexnative") ||
            contains("basespiderguard") ||
            contains(".so") ||
            contains("jni")
    }
}
