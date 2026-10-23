package org.skepsun.kototoro.core.ui.source

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import org.skepsun.kototoro.core.ui.text.LocalUiStrings
import org.skepsun.kototoro.core.ui.text.UiStrings

/**
 * Android's content-type names, shared with the Windows host instead of a second hand-maintained copy in its catalog.
 *
 * [name] is the parser's `ContentType.name` as both hosts carry it in their protocols. Android's resources name every
 * type `content_type_<name>`, so the key needs no table to keep in step; a type neither host knows answers the name
 * itself, exactly as Android's own `when` mapping leaves an unknown type.
 */
fun UiStrings.contentTypeTitle(name: String): String {
    val key = contentTypeTitleKey(name)
    return string(key).takeIf { it != key } ?: name
}

/** The Android resource naming content type [name]: `MANGA` reads `content_type_manga`. */
fun contentTypeTitleKey(name: String): String = "content_type_" + name.lowercase()

/** The Android title of content type [name] in the interface language. */
@Composable
@ReadOnlyComposable
fun contentTypeTitle(name: String): String = LocalUiStrings.current.contentTypeTitle(name)
