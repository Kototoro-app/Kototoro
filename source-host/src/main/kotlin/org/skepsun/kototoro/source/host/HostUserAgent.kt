package org.skepsun.kototoro.source.host

import org.skepsun.kototoro.core.source.SourcePreferenceKind
import org.skepsun.kototoro.core.source.SourcePreferenceNode
import org.skepsun.kototoro.core.source.SourcePreferenceScreen
import org.skepsun.kototoro.core.source.SourcePreferenceUpdateStatus
import org.skepsun.kototoro.core.source.SourcePreferenceValue
import java.lang.reflect.Field
import java.lang.reflect.InvocationTargetException

/**
 * Kototoro's own User-Agent setting for Mihon-family HTTP sources (Mihon, Tsundoku, Aniyomi), which declare no such
 * control themselves; parser sources get theirs from `ConfigKey.UserAgent`. Stored like Android's source settings, in
 * the source's `source_<id>` preferences under `user_agent`. It takes effect by rebuilding the source's default
 * headers (`HttpSource.headers`, a lazy property) from its own `headersBuilder()` with the User-Agent replaced, so
 * every request the source builds from them, images included, carries it. Blank restores the source's default.
 */
internal object HostUserAgent {
    const val NODE_ID = "host:user_agent"
    const val KEY = "user_agent"
    private const val HEADER = "User-Agent"
    private val stateOwner = Any()

    /** The override last installed into this source instance; null is the source's own default. */
    private class Applied(@Volatile var value: String? = null, @Volatile var supported: Boolean = true)

    fun preferences(lease: MihonJarRegistry.SourceLease, context: Any): Any {
        val id = MihonReflection.call(lease.instance, "getId") as Long
        return requireNotNull(MihonReflection.call(context, "getSharedPreferences", "source_$id", 0))
    }

    fun stored(lease: MihonJarRegistry.SourceLease, context: Any): String? =
        (MihonReflection.call(preferences(lease, context), "getString", KEY, null) as? String)?.trim()?.ifEmpty { null }

    /** Installs the stored override if it changed since the last call; cheap when nothing changed. */
    fun apply(lease: MihonJarRegistry.SourceLease, context: Any) {
        val applied = lease.state(stateOwner) { Applied() }
        val wanted = stored(lease, context)
        if (wanted == applied.value || !applied.supported) return
        applied.supported = install(lease, wanted)
        if (applied.supported) applied.value = wanted
    }

    fun node(lease: MihonJarRegistry.SourceLease, context: Any): SourcePreferenceNode {
        val current = stored(lease, context).orEmpty()
        val default = runCatching { defaultUserAgent(lease) }.getOrNull().orEmpty()
        val supported = lease.state(stateOwner) { Applied() }.supported
        return SourcePreferenceNode(
            NODE_ID, KEY, "User-Agent",
            if (supported) "留空使用来源默认值" + (if (default.isNotBlank()) "：$default" else "")
            else "此来源固定了自己的请求头，无法覆盖 User-Agent",
            SourcePreferenceKind.TEXT, enabled = supported, visible = true,
            value = SourcePreferenceValue.Text(current), defaultValue = SourcePreferenceValue.Text(""),
        )
    }

    /** Saves and installs a new value; an invalid header value is rejected without being stored. */
    fun update(lease: MihonJarRegistry.SourceLease, context: Any, value: SourcePreferenceValue): SourcePreferenceUpdateStatus {
        val text = (value as? SourcePreferenceValue.Text)?.value?.trim() ?: return SourcePreferenceUpdateStatus.REJECTED
        if (text.isNotEmpty() && !isValidHeaderValue(text)) return SourcePreferenceUpdateStatus.REJECTED
        val editor = requireNotNull(MihonReflection.call(preferences(lease, context), "edit"))
        if (text.isEmpty()) MihonReflection.call(editor, "remove", KEY) else MihonReflection.call(editor, "putString", KEY, text)
        val saved = MihonReflection.call(editor, "commit") as Boolean
        // Stored either way; the next source call retries an install that failed here.
        runCatching { apply(lease, context) }
        return if (saved) SourcePreferenceUpdateStatus.ACCEPTED else SourcePreferenceUpdateStatus.PERSISTENCE_FAILED
    }

    /**
     * The extension's own controls (if it has any) followed by the host's User-Agent row; appended so extension node
     * positions stay stable, and hosts show `host:` rows first.
     */
    fun screen(lease: MihonJarRegistry.SourceLease, context: Any, http: Boolean,
        native: MihonNativePreferences?): SourcePreferenceScreen {
        val extension = native?.definition()
        if (http) runCatching { apply(lease, context) }
        val host = if (http) listOf(node(lease, context)) else emptyList()
        return SourcePreferenceScreen(lease.descriptor.source, extension?.revision ?: HOST_REVISION,
            extension?.nodes.orEmpty() + host)
    }

    private const val HOST_REVISION = "host"

    /** Header values OkHttp accepts: visible ASCII, spaces and tabs. */
    fun isValidHeaderValue(value: String): Boolean = value.all { it == '\t' || it in ' '..'~' }

    private fun defaultUserAgent(lease: MihonJarRegistry.SourceLease): String? {
        val builder = requireNotNull(MihonReflection.call(lease.instance, "headersBuilder"))
        return MihonReflection.call(builder, "get", HEADER) as? String
    }

    private fun install(lease: MihonJarRegistry.SourceLease, userAgent: String?): Boolean {
        val field = delegateField(lease.instance.javaClass) ?: return false
        val builder = requireNotNull(MihonReflection.call(lease.instance, "headersBuilder"))
        if (userAgent != null) MihonReflection.call(builder, "set", HEADER, userAgent)
        val headers = requireNotNull(MihonReflection.call(builder, "build"))
        // The source's own Kotlin runtime builds the Lazy, so the field keeps a type from its class loader.
        val lazy = try {
            // Declared on the package-private facade part `LazyKt__LazyKt`.
            lease.loader.loadClass("kotlin.LazyKt").getMethod("lazyOf", Any::class.java)
                .apply { isAccessible = true }.invoke(null, headers)
        } catch (error: InvocationTargetException) { throw error.targetException }
        field.set(lease.instance, lazy)
        // A source that overrides `headers` with its own property ignores the delegate.
        return MihonReflection.call(lease.instance, "getHeaders") === headers
    }

    private fun delegateField(type: Class<*>): Field? {
        var current: Class<*>? = type
        while (current != null) {
            val field = current.declaredFields.firstOrNull { it.name == "headers\$delegate" }
            if (field != null) return field.takeIf { it.trySetAccessible() }
            current = current.superclass
        }
        return null
    }
}
