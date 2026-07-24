package org.skepsun.kototoro.reader.ui

import androidx.annotation.StringRes

/** Activity-owned error actions consumed by the Compose reader surface. */
interface ReaderErrorHost {

	fun showReaderErrorDetails(error: Throwable, url: String?)

	fun resolveReaderError(error: Throwable, retry: () -> Unit)

	@StringRes
	fun getReaderErrorActionStringId(error: Throwable): Int
}
