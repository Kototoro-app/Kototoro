package com.lagradost.cloudstream3.utils

import android.content.SharedPreferences

/** androidx.core's `SharedPreferences.edit {}` for the shared DataStore shim; the runtime has no androidx.core. */
internal inline fun SharedPreferences.edit(action: SharedPreferences.Editor.() -> Unit) {
	edit().apply(action).apply()
}
