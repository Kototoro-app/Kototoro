package org.skepsun.kototoro.desktop.app

import org.skepsun.kototoro.core.ui.text.UiStrings

/**
 * Windows' answer to [UiStrings]: names an Android resource like `content_type_manga`.
 *
 * The build exports Android's own catalogs (`:desktop-app:prepareDesktopStrings`), so a shared screen shows Android's
 * text and its translation instead of a hand-maintained copy in `desktop-strings.properties`. The lookup follows the
 * interface language through [DesktopStrings], so a language switch still needs no restart. A name this build does not
 * export answers itself.
 */
internal object DesktopUiStrings : UiStrings {

    override fun string(name: String): String = AndroidStrings[name]

    override fun format(name: String, vararg args: Any?): String = AndroidStrings.format(name, *args)

    override fun array(name: String): List<String> = AndroidStrings.array(name)
}
