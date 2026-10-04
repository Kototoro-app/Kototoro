package org.skepsun.kototoro.source.host

import org.skepsun.kototoro.core.source.*
import java.io.Closeable
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.util.WeakHashMap

/** Implements the parent runtime's Android interfaces without introducing an Android/GPL runtime dependency. */
class MihonPreferenceBridge @JvmOverloads constructor(
    private val loader: ClassLoader,
    private val store: SourcePreferenceStore,
    /** Android R+ sends a null key for clear; platforms emulating earlier targets can disable it. */
    private val notifyClear: Boolean = true,
    /** Platform supplies its main/compatibility dispatcher; the host does not invent an Android Looper. */
    private val dispatchListener: (() -> Unit) -> Unit,
) : Closeable {
    private val preferenceClass = loader.loadClass("android.content.SharedPreferences")
    private val editorClass = loader.loadClass("android.content.SharedPreferences\$Editor")
    private val listenerClass = loader.loadClass("android.content.SharedPreferences\$OnSharedPreferenceChangeListener")
    private val changedMethod = listenerClass.getMethod("onSharedPreferenceChanged", preferenceClass, String::class.java)
    private val bindings = mutableMapOf<String, Binding>()
    @Volatile private var closed = false

    @Synchronized
    fun getSharedPreferences(namespace: String): Any {
        check(!closed) { "Preference bridge is closed" }
        return bindings.getOrPut(namespace) { Binding(store.open(namespace)) }.proxy
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        bindings.values.forEach { it.preferences.removeListener(it.callback) }
        bindings.clear()
    }

    private inner class Binding(val preferences: SourcePreferences) {
        private val listeners = WeakHashMap<Any, Unit>()
        val proxy: Any = Proxy.newProxyInstance(loader, arrayOf(preferenceClass)) { receiver, method, arguments ->
            objectMethod(receiver, method, arguments) ?: invoke(method.name, arguments.orEmpty())
        }
        val callback = SourcePreferenceListener { change ->
            val current = synchronized(listeners) { listeners.keys.toList() }
            val keys: List<String?> = (if (change.cleared && notifyClear) listOf(null) else emptyList()) + change.keys
            dispatchListener {
                if (!closed) for (listener in current) for (key in keys) {
                    val registered = synchronized(listeners) { listeners.containsKey(listener) }
                    if (registered) try { changedMethod.invoke(listener, proxy, key) }
                    catch (error: InvocationTargetException) { throw error.targetException }
                }
            }
        }

        init { preferences.addListener(callback) }

        private fun invoke(name: String, arguments: Array<out Any?>): Any? {
            check(!closed) { "Preference bridge is closed" }
            if (name == "edit") return Editor(preferences).proxy
            if (name == "registerOnSharedPreferenceChangeListener" ||
                name == "unregisterOnSharedPreferenceChangeListener") {
                val listener = requireNotNull(arguments[0])
                require(listenerClass.isInstance(listener))
                synchronized(listeners) {
                    if (name.startsWith("register")) listeners[listener] = Unit else listeners.remove(listener)
                }
                return null
            }
            val values = preferences.snapshot()
            if (name == "getAll") return values.mapValues { rawValue(it.value) }
            val key = arguments[0] as String
            if (name == "contains") return values.containsKey(key)
            val value = values[key] ?: return arguments[1].let {
                if (name == "getStringSet" && it != null) (it as Set<*>).toMutableSet() else it
            }
            return when (name) {
                "getString" -> (value as SourcePreferenceValue.Text).value
                "getStringSet" -> (value as SourcePreferenceValue.TextSet).values.toMutableSet()
                "getBoolean" -> (value as SourcePreferenceValue.Toggle).value
                "getInt" -> (value as SourcePreferenceValue.Integer).value
                "getLong" -> (value as SourcePreferenceValue.LongInteger).value
                "getFloat" -> Float.fromBits((value as SourcePreferenceValue.FloatBits).bits)
                else -> throw UnsupportedOperationException("Unsupported SharedPreferences method: $name")
            }
        }
    }

    private inner class Editor(private val preferences: SourcePreferences) {
        private var clear = false
        private val changes = linkedMapOf<String, SourcePreferenceValue?>()
        val proxy: Any = Proxy.newProxyInstance(loader, arrayOf(editorClass)) { receiver, method, arguments ->
            objectMethod(receiver, method, arguments) ?: invoke(method.name, arguments.orEmpty())
        }

        private fun invoke(name: String, arguments: Array<out Any?>): Any? {
            check(!closed) { "Preference bridge is closed" }
            if (name == "commit" || name == "apply") {
                val edit = synchronized(this) {
                    SourcePreferenceEdit(clear, changes.toMap()).also { clear = false; changes.clear() }
                }
                // Synchronous apply is deliberately stronger than Android's asynchronous disk guarantee.
                val saved = preferences.edit(edit)
                return if (name == "commit") saved else null
            }
            synchronized(this) {
                if (name == "clear") clear = true else {
                    val key = arguments[0] as String
                    changes[key] = when (name) {
                        "remove" -> null
                        "putString" -> (arguments[1] as String?)?.let(SourcePreferenceValue::Text)
                        "putStringSet" -> (arguments[1] as Set<*>?)?.map {
                            it as String
                        }?.toSet()?.let(SourcePreferenceValue::TextSet)
                        "putBoolean" -> SourcePreferenceValue.Toggle(arguments[1] as Boolean)
                        "putInt" -> SourcePreferenceValue.Integer(arguments[1] as Int)
                        "putLong" -> SourcePreferenceValue.LongInteger(arguments[1] as Long)
                        "putFloat" -> SourcePreferenceValue.FloatBits((arguments[1] as Float).toRawBits())
                        else -> throw UnsupportedOperationException("Unsupported SharedPreferences.Editor method: $name")
                    }
                }
            }
            return proxy
        }
    }

    private fun rawValue(value: SourcePreferenceValue): Any = when (value) {
        is SourcePreferenceValue.Text -> value.value
        is SourcePreferenceValue.TextSet -> value.values.toMutableSet()
        is SourcePreferenceValue.Toggle -> value.value
        is SourcePreferenceValue.Integer -> value.value
        is SourcePreferenceValue.LongInteger -> value.value
        is SourcePreferenceValue.FloatBits -> Float.fromBits(value.bits)
    }

    private fun objectMethod(receiver: Any, method: Method, arguments: Array<out Any?>?): Any? = when (method.name) {
        "equals" -> receiver === arguments?.get(0)
        "hashCode" -> System.identityHashCode(receiver)
        "toString" -> "MihonPreferenceBridge.${method.declaringClass.simpleName}"
        else -> null
    }
}
