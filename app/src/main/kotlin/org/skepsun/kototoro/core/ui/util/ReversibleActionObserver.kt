package org.skepsun.kototoro.core.ui.util

import android.view.View
import android.widget.Toast
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.flow.FlowCollector
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.util.ext.findActivity
import org.skepsun.kototoro.main.ui.owners.BottomSheetOwner

class ReversibleActionObserver(
    private val snackbarHost: View,
) : FlowCollector<ReversibleAction> {

    override suspend fun emit(value: ReversibleAction) {
        val handle = value.handle
        val length = if (handle == null) Snackbar.LENGTH_SHORT else Snackbar.LENGTH_LONG
        val snackbar = try {
            Snackbar.make(snackbarHost, value.stringResId, length)
        } catch (_: RuntimeException) {
            Toast.makeText(snackbarHost.context, value.stringResId, Toast.LENGTH_SHORT).show()
            return
        }
        when (val activity = snackbarHost.context.findActivity()) {
            is BottomSheetOwner -> snackbar.anchorView = activity.bottomSheet
        }
        if (handle != null) {
            snackbar.setAction(R.string.undo) { handle.reverseAsync() }
        }
        snackbar.show()
    }
}
