package org.skepsun.kototoro.migration.ui

import android.content.Context
import org.skepsun.kototoro.core.model.getTitle
import org.skepsun.kototoro.core.model.isUnresolved
import org.skepsun.kototoro.parsers.model.ContentSource

/**
 * Title for migration screens. An unresolved source would otherwise render as a
 * "loading…" placeholder, which hides which source actually disappeared.
 */
fun ContentSource.migrationTitle(context: Context): String = if (isUnresolved) name else getTitle(context)
