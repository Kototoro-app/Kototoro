package org.skepsun.kototoro.migration.data

/**
 * A favourite's remote entry on a tracking site ([service] as in `ScrobblerService`). [manual] is a user-confirmed
 * link or an account binding (scrobbling); automatic metadata matches are not manual and can be wrong.
 */
data class LibraryTrackingId(
    val mangaId: Long,
    val service: Int,
    val remoteId: Long,
    val manual: Boolean,
)
