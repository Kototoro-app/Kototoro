package com.lagradost.cloudstream3.utils

import android.content.Context
import android.util.TypedValue
import androidx.fragment.app.FragmentActivity

/** The part of Cloudstream's UI helpers plugins call from their own screens. */
object UIHelper {
	/** The colour a theme attribute (such as `colorPrimary`) resolves to in this context's theme. */
	fun Context.colorFromAttribute(attribute: Int): Int {
		val value = TypedValue()
		theme.resolveAttribute(attribute, value, true)
		return value.data
	}

	/** Closes the page a plugin opened under [fragName], or goes back when it is not there. */
	fun FragmentActivity.popCurrentPage(fragName: String) {
		val fragment = supportFragmentManager.findFragmentByTag(fragName)
		if (fragment != null) {
			supportFragmentManager.beginTransaction().remove(fragment).commitAllowingStateLoss()
		} else {
			onBackPressedDispatcher.onBackPressed()
		}
	}
}
