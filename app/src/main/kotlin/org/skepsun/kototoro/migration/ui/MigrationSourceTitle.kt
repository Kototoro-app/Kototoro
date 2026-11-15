package org.skepsun.kototoro.migration.ui

import android.content.Context
import org.skepsun.kototoro.core.model.getTitle
import org.skepsun.kototoro.core.model.isUnresolved
import org.skepsun.kototoro.parsers.model.ContentSource

/**
 * Title for migration screens. An unresolved source would otherwise render as a
 * "loading…" placeholder, which hides which source actually disappeared.
 */
fun ContentSource.migrationTitle(context: Context): String =
    if (isUnresolved) unresolvedSourceLabel(name) else getTitle(context)

/**
 * A stored name such as `MIHON_7000000000000000001` is an ecosystem prefix and a numeric id. Shown
 * raw it reads as an internal error, so it becomes `Mihon …0001`: the ecosystem tells the user which
 * app the extension came from and the tail still tells two missing sources apart. Names that are not
 * of that shape (rule names, parser names) are already readable and stay as they are.
 */
internal fun unresolvedSourceLabel(name: String): String {
    val separator = name.indexOf('_')
    if (separator <= 0) return name
    val prefix = name.substring(0, separator)
    val id = name.substring(separator + 1)
    if (id.length < MIN_NUMERIC_ID_LENGTH || !id.all(Char::isDigit) || !prefix.all(Char::isLetter)) return name
    val ecosystem = prefix.lowercase().replaceFirstChar(Char::uppercaseChar)
    return "$ecosystem …${id.takeLast(VISIBLE_ID_DIGITS)}"
}

private const val MIN_NUMERIC_ID_LENGTH = 8
private const val VISIBLE_ID_DIGITS = 4
