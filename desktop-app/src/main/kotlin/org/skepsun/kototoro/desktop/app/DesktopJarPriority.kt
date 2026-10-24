package org.skepsun.kototoro.desktop.app

import org.skepsun.kototoro.parserhost.ParserPluginRegistry

/**
 * Android's `jar_priority_order`, which decides which JAR serves a source that several parser JARs declare.
 *
 * Windows installs parser plugins instead of Android's extension JARs, so the names in the saved order are the
 * plugin ids the parser host ranks by ([ParserPluginRegistry] resolves a shared source name exactly the way Android
 * picks between two JARs). The value keeps Android's comma separated text, so a copied preference means the same
 * thing on both platforms; anything it does not mention keeps the registry's own default ranking.
 */
internal const val JAR_PRIORITY_ORDER_KEY = "jar_priority_order"

/** The saved order first, then the registry default, without duplicates — [ParserPluginRegistry] ranks the first. */
internal fun parserPluginPriority(savedOrder: String): List<String> =
    (savedOrder.parseJarPriorityNames() + ParserPluginRegistry.DEFAULT_PRIORITY).distinctBy { it.lowercase() }

/** Android stores the order as one comma separated string; blank and repeated entries never reach the ranking. */
internal fun String.parseJarPriorityNames(): List<String> =
    split(',').map { it.trim() }.filter { it.isNotEmpty() }
