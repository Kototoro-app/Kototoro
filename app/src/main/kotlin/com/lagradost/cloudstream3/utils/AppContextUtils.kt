package com.lagradost.cloudstream3.utils

import android.content.DialogInterface
import androidx.appcompat.app.AlertDialog
import com.lagradost.cloudstream3.ui.settings.Globals

/** The Cloudstream app helpers plugins call; same signatures as the official `AppContextUtils`. */
object AppContextUtils {
	/** Moves the focus to [buttonFocus] on a TV or the emulator layout, so a remote lands on a button. */
	fun AlertDialog.setDefaultFocus(buttonFocus: Int = DialogInterface.BUTTON_NEGATIVE) {
		if (!Globals.isLayout(Globals.TV or Globals.EMULATOR)) return
		getButton(buttonFocus)?.run {
			isFocusableInTouchMode = true
			requestFocus()
		}
	}
}
