package org.koitharu.kotatsu.parsers.util.json

import androidx.collection.ArraySet
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import org.koitharu.kotatsu.parsers.util.nullIfEmpty
import java.util.*
import kotlin.contracts.contract

public fun String.toJSONObjectOrNull(): JSONObject? = try {
	JSONObject(this)
} catch (_: JSONException) {
	null
}

public fun String.toJSONArrayOrNull(): JSONArray? = try {
	JSONArray(this)
} catch (_: JSONException) {
	null
}

public inline fun <R, C : MutableCollection<in R>> JSONArray.mapJSONTo(
	destination: C,
	block: (JSONObject) -> R,
): C {
	val len = length()
	for (i in 0 until len) {
		val jo = getJSONObject(i)
		destination.add(block(jo))
	}
	return destination
}

public inline fun <R, C : MutableCollection<in R>> JSONArray.mapJSONNotNullTo(
	destination: C,
	block: (JSONObject) -> R?,
): C {
	val len = length()
	for (i in 0 until len) {
		val jo = getJSONObject(i)
		destination.add(block(jo) ?: continue)
	}
	return destination
}

public inline fun <T> JSONArray.mapJSON(block: (JSONObject) -> T): List<T> {
	return mapJSONTo(ArrayList(length()), block)
}

public inline fun <T : Any> JSONArray.mapJSONNotNull(block: (JSONObject) -> T?): List<T> {
	return mapJSONNotNullTo(ArrayList(length()), block)
}

public inline fun <T> JSONArray.mapJSONToSet(mapper: (JSONObject) -> T): Set<T> {
	return mapJSONTo(ArraySet<T>(length()), mapper)
}

public inline fun <T : Any> JSONArray.mapJSONNotNullToSet(mapper: (JSONObject) -> T?): Set<T> {
	return mapJSONNotNullTo(ArraySet<T>(length()), mapper)
}

public fun <T> JSONArray.mapJSONIndexed(block: (Int, JSONObject) -> T): List<T> {
	val len = length()
	val result = ArrayList<T>(len)
	for (i in 0 until len) {
		val jo = getJSONObject(i)
		result.add(block(i, jo))
	}
	return result
}

public fun JSONObject.getStringOrNull(name: String): String? = opt(name)?.takeUnless {
	it === JSONObject.NULL
}?.toString()?.nullIfEmpty()

public fun JSONObject.getBooleanOrDefault(name: String, defaultValue: Boolean): Boolean {
	return when (val rawValue = opt(name)) {
		null, JSONObject.NULL -> defaultValue
		is Boolean -> rawValue
		is Number -> rawValue.toInt() != 0
		is String -> rawValue.lowercase(Locale.ROOT).toBooleanStrictOrNull() ?: defaultValue
		else -> defaultValue
	}
}

public fun JSONObject.getLongOrDefault(name: String, defaultValue: Long): Long {
	return when (val rawValue = opt(name)) {
		null, JSONObject.NULL -> defaultValue
		is Long -> rawValue
		is Number -> rawValue.toLong()
		is String -> rawValue.toLongOrNull() ?: defaultValue
		else -> defaultValue
	}
}

public fun JSONObject.getIntOrDefault(name: String, defaultValue: Int): Int {
	return when (val rawValue = opt(name)) {
		null, JSONObject.NULL -> defaultValue
		is Int -> rawValue
		is Number -> rawValue.toInt()
		is String -> rawValue.toIntOrNull() ?: defaultValue
		else -> defaultValue
	}
}

public fun JSONObject.getDoubleOrDefault(name: String, defaultValue: Double): Double {
	return when (val rawValue = opt(name)) {
		null, JSONObject.NULL -> defaultValue
		is Double -> rawValue
		is Number -> rawValue.toDouble()
		is String -> rawValue.toDoubleOrNull() ?: defaultValue
		else -> defaultValue
	}
}

public fun JSONObject.getFloatOrDefault(name: String, defaultValue: Float): Float {
	return when (val rawValue = opt(name)) {
		null, JSONObject.NULL -> defaultValue
		is Float -> rawValue
		is Number -> rawValue.toFloat()
		is String -> rawValue.toFloatOrNull() ?: defaultValue
		else -> defaultValue
	}
}

public fun <E : Enum<E>> JSONObject.getEnumValueOrNull(name: String, enumClass: Class<E>): E? {
	val enumName = getStringOrNull(name) ?: return null
	return enumClass.enumConstants?.find { x ->
		enumName.equals(x.name, ignoreCase = true)
	}
}

public fun <E : Enum<E>> JSONObject.getEnumValueOrDefault(name: String, defaultValue: E): E {
	return getEnumValueOrNull(name, defaultValue.javaClass) ?: defaultValue
}

public fun JSONArray?.isNullOrEmpty(): Boolean {
	contract {
		returns(false) implies (this@isNullOrEmpty != null)
	}

	return this == null || this.length() == 0
}

public fun <T : Any> JSONArray.asTypedList(typeClass: Class<T>): List<T> {
	return JSONArrayTypedListWrapper(this, typeClass)
}

public inline fun <reified T : Any> JSONArray.asTypedList(): List<T> = asTypedList(T::class.java)

public fun <T : Any> JSONObject.entries(typeClass: Class<T>): Iterable<Map.Entry<String, T>> {
	return JSONObjectTypedIterableWrapper(this, typeClass)
}

public inline fun <reified T : Any> JSONObject.entries(): Iterable<Map.Entry<String, T>> = entries(T::class.java)

public fun JSONArray.toStringSet(): Set<String> {
	val set = ArraySet<String>(length())
	repeat(length()) { i ->
		val str = optString(i)
		if (!str.isNullOrEmpty()) {
			set.add(str)
		}
	}
	return set
}
