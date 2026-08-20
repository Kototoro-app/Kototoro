package org.skepsun.kototoro.core.exceptions.resolve

import android.content.DialogInterface
import android.view.View
import androidx.core.util.Consumer
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.util.ext.getDisplayMessage
import org.skepsun.kototoro.core.util.ext.isSerializable
import org.skepsun.kototoro.parsers.exception.ParseException

class DialogErrorObserver(
	host: View,
	resolver: ExceptionResolver?,
	private val onResolved: Consumer<Boolean>?,
) : ErrorObserver(host, resolver, onResolved) {

	constructor(
		host: View,
	) : this(host, null, null)

	override suspend fun emit(value: Throwable) {
		val listener = DialogListener(value)
		val dialogBuilder = MaterialAlertDialogBuilder(activity ?: host.context)
			.setMessage(value.getDisplayMessage(host.context.resources))
			.setNegativeButton(R.string.close, listener)
			.setOnCancelListener(listener)
		if (canResolve(value)) {
			dialogBuilder.setPositiveButton(getResolveStringId(value), listener)
		} else if (value is ParseException) {
			val router = router()
			if (router != null && value.isSerializable()) {
				dialogBuilder.setPositiveButton(R.string.details) { _, _ ->
					router.showErrorDialog(value)
				}
			}
		}
		val dialog = dialogBuilder.create()
		if (activity != null) {
			dialog.setOwnerActivity(activity)
		}
		dialog.show()
	}

	private inner class DialogListener(
		private val error: Throwable,
	) : DialogInterface.OnClickListener, DialogInterface.OnCancelListener {

		override fun onClick(dialog: DialogInterface?, which: Int) {
			when (which) {
				DialogInterface.BUTTON_NEGATIVE -> onResolved?.accept(false)
				DialogInterface.BUTTON_POSITIVE -> resolve(error)
			}
		}

		override fun onCancel(dialog: DialogInterface?) {
			onResolved?.accept(false)
		}
	}
}
