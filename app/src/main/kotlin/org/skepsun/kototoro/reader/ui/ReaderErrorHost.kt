package org.skepsun.kototoro.reader.ui

import androidx.annotation.StringRes

/** Activity-owned error actions shared by the temporary Fragment host and direct Compose hosting. */
interface ReaderErrorHost {

	fun showReaderErrorDetails(error: Throwable, url: String?)

	fun resolveReaderError(error: Throwable, retry: () -> Unit)

	@StringRes
	fun getReaderErrorActionStringId(error: Throwable): Int
}
