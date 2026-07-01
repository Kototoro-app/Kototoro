package com.lagradost.cloudstream3

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.lang.ref.WeakReference

class CloudStreamApp {
	companion object {
		private const val PREFS_NAME = "cloudstream_app_compat"

		@PublishedApi
		internal val gson = Gson()
		private var contextRef: WeakReference<Context>? = null

		var context: Context?
			get() = contextRef?.get()
			set(value) {
				contextRef = WeakReference(value ?: return)
				com.lagradost.api.setContext(WeakReference(value as Any))
			}

		tailrec fun Context.getActivity(): android.app.Activity? {
			return when (this) {
				is android.app.Activity -> this
				is ContextWrapper -> baseContext.getActivity()
				else -> null
			}
		}

		fun <T : Any> getKeyClass(path: String, valueType: Class<T>): T? {
			return read(path)?.let { json ->
				runCatching { gson.fromJson(json, valueType) }.getOrNull()
			}
		}

		fun <T : Any> setKeyClass(path: String, value: T) {
			write(path, value)
		}

		fun removeKeys(folder: String): Int? {
			val prefs = prefs() ?: return null
			val prefix = "$folder/"
			val keys = prefs.all.keys.filter { it == folder || it.startsWith(prefix) }
			if (keys.isEmpty()) return 0
			prefs.edit().apply {
				keys.forEach(::remove)
			}.apply()
			return keys.size
		}

		fun <T> setKey(path: String, value: T) {
			write(path, value)
		}

		fun <T> setKey(folder: String, path: String, value: T) {
			write("$folder/$path", value)
		}

		inline fun <reified T : Any> getKey(path: String, defVal: T?): T? {
			return getKey(path) ?: defVal
		}

		inline fun <reified T : Any> getKey(path: String): T? {
			return read(path)?.let { json ->
				runCatching {
					gson.fromJson<T>(json, object : TypeToken<T>() {}.type)
				}.getOrNull()
			}
		}

		inline fun <reified T : Any> getKey(folder: String, path: String): T? {
			return getKey("$folder/$path")
		}

		inline fun <reified T : Any> getKey(folder: String, path: String, defVal: T?): T? {
			return getKey(folder, path) ?: defVal
		}

		fun getKeys(folder: String): List<String>? {
			val prefs = prefs() ?: return null
			val prefix = "$folder/"
			return prefs.all.keys
				.filter { it.startsWith(prefix) }
				.map { it.removePrefix(prefix) }
		}

		fun removeKey(folder: String, path: String) {
			removeKey("$folder/$path")
		}

		fun removeKey(path: String) {
			prefs()?.edit()?.remove(path)?.apply()
		}

		fun openBrowser(url: String, fallbackWebView: Boolean = false, fragment: Fragment? = null) {
			val ctx = fragment?.context ?: context ?: return
			runCatching {
				ctx.startActivity(
					Intent(Intent.ACTION_VIEW, Uri.parse(url))
						.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
				)
			}
		}

		fun openBrowser(url: String, activity: FragmentActivity?) {
			openBrowser(url, fragment = activity?.supportFragmentManager?.fragments?.lastOrNull())
		}

		private fun prefs() = context?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

		@PublishedApi
		internal fun read(path: String): String? {
			return prefs()?.getString(path, null)
		}

		private fun <T> write(path: String, value: T) {
			prefs()?.edit()?.putString(path, gson.toJson(value))?.apply()
		}
	}
}
