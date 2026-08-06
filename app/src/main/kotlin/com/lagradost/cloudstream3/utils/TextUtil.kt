package com.lagradost.cloudstream3.utils

import android.content.Context
import android.util.Log
import androidx.annotation.StringRes
import com.lagradost.cloudstream3.mvvm.logError

sealed class UiText {

	data class DynamicString(val value: String) : UiText() {
		override fun toString(): String = value
	}

	class StringResource(
		@StringRes val resId: Int,
		val args: List<Any>,
	) : UiText() {
		override fun toString(): String = "resId = $resId\nargs = $args"

		override fun equals(other: Any?): Boolean =
			other is StringResource && resId == other.resId && args == other.args

		override fun hashCode(): Int = 31 * resId + args.hashCode()
	}

	fun asStringNull(context: Context?): String? = try {
		asString(context ?: return null)
	} catch (error: Exception) {
		Log.e(TAG, "Got invalid data from $this")
		logError(error)
		null
	}

	fun asString(context: Context): String = when (this) {
		is DynamicString -> value
		is StringResource -> context.getString(resId).let { value ->
			if (args.isEmpty()) {
				value
			} else {
				value.format(*args.map { if (it is UiText) it.asString(context) else it }.toTypedArray())
			}
		}
	}

	companion object {
		const val TAG = "UiText"
	}
}

fun txt(value: String): UiText = UiText.DynamicString(value)

@JvmName("txtNull")
fun txt(value: String?): UiText? = value?.let(UiText::DynamicString)

fun txt(@StringRes resId: Int, vararg args: Any): UiText = UiText.StringResource(resId, args.toList())

@JvmName("txtNull")
fun txt(@StringRes resId: Int?, vararg args: Any?): UiText? {
	if (resId == null || args.any { it == null }) return null
	return UiText.StringResource(resId, args.filterNotNull())
}
