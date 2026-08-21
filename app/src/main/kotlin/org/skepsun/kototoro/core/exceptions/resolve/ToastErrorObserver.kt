package org.skepsun.kototoro.core.exceptions.resolve

import android.view.View
import android.widget.Toast
import org.skepsun.kototoro.core.util.ext.getDisplayMessage

class ToastErrorObserver(
    host: View,
) : ErrorObserver(host, null, null) {

    override suspend fun emit(value: Throwable) {
        val toast = Toast.makeText(host.context, value.getDisplayMessage(host.context.resources), Toast.LENGTH_SHORT)
        toast.show()
    }
}
