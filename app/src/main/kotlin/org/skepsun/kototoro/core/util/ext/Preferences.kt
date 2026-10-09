package org.skepsun.kototoro.core.util.ext

import android.content.SharedPreferences
import androidx.collection.ArraySet
import androidx.core.content.edit
import androidx.preference.ListPreference
import androidx.preference.MultiSelectListPreference
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import org.json.JSONArray

fun ListPreference.setDefaultValueCompat(defaultValue: String) {
    if (value == null) {
        value = defaultValue
    }
}

fun MultiSelectListPreference.setDefaultValueCompat(defaultValue: Set<String>) {
    setDefaultValue(defaultValue) // FIXME not working
}

fun <E : Enum<E>> SharedPreferences.getEnumValue(key: String, enumClass: Class<E>): E? {
    val stringValue = getString(key, null) ?: return null
    return enumClass.enumConstants?.find {
        it.name == stringValue
    }
}

fun <E : Enum<E>> SharedPreferences.getEnumValue(key: String, defaultValue: E): E {
    return getEnumValue(key, defaultValue.javaClass) ?: defaultValue
}

fun <E : Enum<E>> SharedPreferences.Editor.putEnumValue(key: String, value: E?) {
    putString(key, value?.name)
}

fun SharedPreferences.observeChanges(): Flow<String?> = callbackFlow {
    val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        trySendBlocking(key)
    }
    registerOnSharedPreferenceChangeListener(listener)
    awaitClose {
        unregisterOnSharedPreferenceChangeListener(listener)
    }
}

fun <T> SharedPreferences.observe(key: String, valueProducer: suspend () -> T): Flow<T> = flow {
    emit(valueProducer())
    observeChanges().collect { upstreamKey ->
        if (upstreamKey == key) {
            emit(valueProducer())
        }
    }
}.distinctUntilChanged()

/**
 * Storage class chosen for a raw preference value by [preferenceStorageOp].
 */
internal enum class PreferenceStorageType {
    BOOLEAN,
    INT,
    LONG,
    FLOAT,
    STRING,
    STRING_SET,
}

/**
 * Maps a raw preference value (exactly as parsed by org.json from a backup
 * SETTINGS file) onto the app-wide numeric storage policy:
 *
 *  - every integral value that fits in [Int] is stored as `Int`, so any plain
 *    `getInt()` reader (novel marking settings, backup settings screen, ...)
 *    can never hit a `ClassCastException` after a settings restore;
 *  - only out-of-range `Long` values (epoch timestamps, big counters) stay
 *    `Long`, which every `getSafeLong()`/`getSafeFloat()` reader already handles.
 *
 * The policy is idempotent: restoring the same backup repeatedly never changes
 * the underlying storage type of a key.
 */
internal fun preferenceStorageOp(value: Any?): Pair<PreferenceStorageType, Any>? =
    when (value) {
        is Boolean -> PreferenceStorageType.BOOLEAN to value
        is Int -> PreferenceStorageType.INT to value
        is Long ->
            if (value in Int.MIN_VALUE..Int.MAX_VALUE) {
                PreferenceStorageType.INT to value.toInt()
            } else {
                PreferenceStorageType.LONG to value
            }
        is Float -> PreferenceStorageType.FLOAT to value
        is Double -> PreferenceStorageType.FLOAT to value.toFloat()
        is String -> PreferenceStorageType.STRING to value
        is JSONArray -> PreferenceStorageType.STRING_SET to value.toStringSet()
        is Set<*> -> PreferenceStorageType.STRING_SET to value.filterIsInstance<String>().toSet()
        else -> null
    }

fun SharedPreferences.Editor.putAll(values: Map<String, *>) {
    values.forEach { (key, value) ->
        val (type, stored) = preferenceStorageOp(value) ?: return@forEach
        when (type) {
            PreferenceStorageType.BOOLEAN -> putBoolean(key, stored as Boolean)
            PreferenceStorageType.INT -> putInt(key, stored as Int)
            PreferenceStorageType.LONG -> putLong(key, stored as Long)
            PreferenceStorageType.FLOAT -> putFloat(key, stored as Float)
            PreferenceStorageType.STRING -> putString(key, stored as String)
            PreferenceStorageType.STRING_SET -> putStringSet(key, stored as Set<String>)
        }
    }
}

/**
 * Reads [key] as [Int] even when an older settings restore stored it as [Long]
 * (the pre-unification backup policy promoted every Int to Long). Recovers the
 * value and immediately rewrites the key as `Int`, so both this call and later
 * plain `getInt()` readers — such as modules that do not go through
 * [org.skepsun.kototoro.core.prefs.AppSettings] — keep working afterwards.
 */
fun SharedPreferences.getSafeInt(key: String, defValue: Int): Int {
    return try {
        getInt(key, defValue)
    } catch (_: ClassCastException) {
        val recovered = when (val raw = all[key]) {
            is Int -> raw
            is Long -> raw.takeIf { it in Int.MIN_VALUE..Int.MAX_VALUE }?.toInt()
            else -> null
        } ?: defValue
        edit { putInt(key, recovered) }
        recovered
    }
}

fun SharedPreferences.getSafeFloat(key: String, defaultValue: Float): Float {
    return try {
        getFloat(key, defaultValue)
    } catch (_: ClassCastException) {
        when (val raw = all[key]) {
            is Int -> raw.toFloat()
            is Long -> raw.toFloat()
            is Double -> raw.toFloat()
            is String -> raw.toFloatOrNull() ?: defaultValue
            else -> defaultValue
        }.also {
            edit { putFloat(key, it) }
        }
    }
}

internal fun JSONArray.toStringSet(): Set<String> {
    val len = length()
    val result = ArraySet<String>(len)
    for (i in 0 until len) {
        result.add(getString(i))
    }
    return result
}
