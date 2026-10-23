package org.skepsun.kototoro.core.ui.text

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * Text for the presentation both hosts share, addressed by one of Android's resource names
 * (`content_type_manga`).
 *
 * Android owns the catalog: Weblate translates `app/src/main/res/values…/strings.xml`, so a shared screen names a
 * string instead of carrying its own copy of the text. Android answers the name from `R.string`; Windows answers it
 * from the catalog `:desktop-app:prepareDesktopStrings` exports from the same files, so both hosts read one
 * translation instead of two that drift apart.
 *
 * A host injects its implementation with [LocalUiStrings]. Without one every lookup answers the name itself, which
 * keeps previews, tests and hosts that have not been migrated yet rendering instead of crashing.
 */
interface UiStrings {
    /** The text named [name], or [name] itself when this catalog has no such string. */
    fun string(name: String): String

    /** [string] with [args] substituted, in the platform's own `String.format` convention. */
    fun format(name: String, vararg args: Any?): String

    /** The items of the string array named [name]; empty when this catalog has no such array. */
    fun array(name: String): List<String> = emptyList()
}

/** The catalog shared screens read; a host provides it once, at its theme root. */
val LocalUiStrings = staticCompositionLocalOf<UiStrings> { NameOnlyUiStrings }

/** Stands in for a missing catalog: every lookup answers with the name, so text never fails to compose. */
internal object NameOnlyUiStrings : UiStrings {
    override fun string(name: String): String = name
    override fun format(name: String, vararg args: Any?): String = name
}

/** The text named [name] in the interface language; [args] are substituted when present. */
@Composable
@ReadOnlyComposable
fun uiString(name: String, vararg args: Any?): String {
    val strings = LocalUiStrings.current
    return if (args.isEmpty()) strings.string(name) else strings.format(name, *args)
}

/** The items of the string array named [name] in the interface language. */
@Composable
@ReadOnlyComposable
fun uiStringArray(name: String): List<String> = LocalUiStrings.current.array(name)
