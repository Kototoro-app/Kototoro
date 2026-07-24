package org.skepsun.kototoro.core.exceptions.resolve

import android.view.View
import android.widget.Toast
import androidx.core.util.Consumer
import androidx.fragment.app.Fragment
import com.google.android.material.snackbar.Snackbar
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.util.ext.getDisplayMessage
import org.skepsun.kototoro.core.util.ext.isSerializable
import org.skepsun.kototoro.main.ui.owners.BottomSheetOwner
import org.skepsun.kototoro.parsers.exception.ParseException

class SnackbarErrorObserver(
	host: View,
	fragment: Fragment?,
	resolver: ExceptionResolver?,
	onResolved: Consumer<Boolean>?,
) : ErrorObserver(host, fragment, resolver, onResolved) {

	constructor(
		host: View,
		fragment: Fragment?,
	) : this(host, fragment, null, null)

	override suspend fun emit(value: Throwable) {
		val message = value.getDisplayMessage(host.context.resources)
		val snackbar = try {
			Snackbar.make(host, message, Snackbar.LENGTH_SHORT)
		} catch (_: RuntimeException) {
			Toast.makeText(host.context, message, Toast.LENGTH_SHORT).show()
			return
		}
		when (activity) {
			is BottomSheetOwner -> snackbar.anchorView = activity.bottomSheet
		}
		if (canResolve(value)) {
			snackbar.setAction(getResolveStringId(value)) {
				resolve(value)
			}
		} else if (value is ParseException) {
			val router = router()
			if (router != null && value.isSerializable()) {
				snackbar.setAction(R.string.details) {
					router.showErrorDialog(value)
				}
			}
		}
		snackbar.show()
	}
}
