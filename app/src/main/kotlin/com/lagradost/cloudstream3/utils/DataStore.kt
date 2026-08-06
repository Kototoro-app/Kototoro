@file:OptIn(com.lagradost.cloudstream3.InternalAPI::class)

package com.lagradost.cloudstream3.utils

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import androidx.preference.PreferenceManager
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJsonLiteral
import kotlin.reflect.KClass
import kotlin.reflect.KProperty

class PreferenceDelegate<T : Any>(
	val key: String,
	val default: T,
) {
	private val klass: KClass<out T> = default::class
	private var cache: T? = null

	operator fun getValue(self: Any?, property: KProperty<*>): T =
		cache ?: CloudStreamPreferences.getKeyClass(key, klass.java)
			.also { cache = it }
			?: default

	operator fun setValue(self: Any?, property: KProperty<*>, value: T?) {
		cache = value
		if (value == null) {
			CloudStreamPreferences.removeKey(key)
		} else {
			CloudStreamPreferences.setKeyClass(key, value)
		}
	}
}

data class Editor(
	val editor: SharedPreferences.Editor,
) {
	fun <T> setKeyRaw(path: String, value: T) {
		when {
			value is Set<*> && value.all { it is String } -> {
				@Suppress("UNCHECKED_CAST")
				editor.putStringSet(path, value as Set<String>)
			}
			value is Boolean -> editor.putBoolean(path, value)
			value is Int -> editor.putInt(path, value)
			value is String -> editor.putString(path, value)
			value is Float -> editor.putFloat(path, value)
			value is Long -> editor.putLong(path, value)
		}
	}

	fun apply() {
		editor.apply()
	}
}

object DataStore {

	@Deprecated(
		"Use com.lagradost.cloudstream3.mapper directly",
		level = DeprecationLevel.ERROR,
		replaceWith = ReplaceWith("com.lagradost.cloudstream3.mapper"),
	)
	val mapper = com.lagradost.cloudstream3.mapper

	fun Context.getSharedPrefs(): SharedPreferences =
		getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

	fun getFolderName(folder: String, path: String): String = "$folder/$path"

	fun editor(context: Context, isEditingAppSettings: Boolean = false): Editor {
		val preferences = if (isEditingAppSettings) {
			context.getDefaultSharedPrefs()
		} else {
			context.getSharedPrefs()
		}
		return Editor(preferences.edit())
	}

	fun Context.getDefaultSharedPrefs(): SharedPreferences =
		PreferenceManager.getDefaultSharedPreferences(this)

	fun Context.getKeys(folder: String): List<String> {
		val prefix = folder.trimEnd('/') + "/"
		return getSharedPrefs().all.keys.filter { it.startsWith(prefix) }
	}

	fun Context.removeKey(folder: String, path: String) {
		removeKey(getFolderName(folder, path))
	}

	fun Context.containsKey(folder: String, path: String): Boolean =
		containsKey(getFolderName(folder, path))

	fun Context.containsKey(path: String): Boolean = getSharedPrefs().contains(path)

	fun Context.removeKey(path: String) {
		runCatching {
			getSharedPrefs().edit { remove(path) }
		}.onFailure(::logError)
	}

	fun Context.removeKeys(folder: String): Int {
		val keys = getKeys(folder)
		return runCatching {
			getSharedPrefs().edit {
				keys.forEach(::remove)
			}
			keys.size
		}.onFailure(::logError).getOrDefault(0)
	}

	fun <T> Context.setKey(path: String, value: T) {
		runCatching {
			getSharedPrefs().edit { putString(path, value?.toJsonLiteral()) }
		}.onFailure(::logError)
	}

	fun <T : Any> Context.getKey(path: String, valueType: Class<T>): T? =
		runCatching {
			val json = getSharedPrefs().getString(path, null) ?: return null
			parseJson(json, valueType.kotlin)
		}.getOrNull()

	fun <T> Context.setKey(folder: String, path: String, value: T) {
		setKey(getFolderName(folder, path), value)
	}

	@Deprecated("Use AppUtils.parseJson", level = DeprecationLevel.WARNING)
	inline fun <reified T : Any> String.toKotlinObject(): T = parseJson(this)

	@Deprecated("Use AppUtils.parseJson", level = DeprecationLevel.WARNING)
	fun <T : Any> String.toKotlinObject(valueType: Class<T>): T = parseJson(this, valueType.kotlin)

	inline fun <reified T : Any> Context.getKey(path: String, defVal: T?): T? =
		runCatching {
			val json = getSharedPrefs().getString(path, null) ?: return defVal
			parseJson<T>(json)
		}.getOrNull()

	inline fun <reified T : Any> Context.getKey(path: String): T? = getKey(path, null)

	inline fun <reified T : Any> Context.getKey(folder: String, path: String): T? =
		getKey(getFolderName(folder, path), null)

	inline fun <reified T : Any> Context.getKey(folder: String, path: String, defVal: T?): T? =
		getKey(getFolderName(folder, path), defVal) ?: defVal
}

private const val PREFERENCES_NAME = "rebuild_preference"

private object CloudStreamPreferences {
	fun <T : Any> getKeyClass(path: String, valueType: Class<T>): T? =
		com.lagradost.cloudstream3.CloudStreamApp.getKeyClass(path, valueType)

	fun <T : Any> setKeyClass(path: String, value: T) {
		com.lagradost.cloudstream3.CloudStreamApp.setKeyClass(path, value)
	}

	fun removeKey(path: String) {
		com.lagradost.cloudstream3.CloudStreamApp.removeKey(path)
	}
}
