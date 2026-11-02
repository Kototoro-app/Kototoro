package org.skepsun.kototoro.desktop.app

import org.skepsun.kototoro.core.source.SourceContent
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection

/** The text a selection's "share" action produces, plus how many works it covers. */
internal data class DesktopShareText(val text: String, val count: Int) {
    val isEmpty: Boolean get() = count == 0
}

/**
 * Android's `ShareHelper` payload for the same selection, minus the `kototoro://` deep link Windows cannot
 * register: a single work pastes "title, blank line, link" exactly like Android's single-link share, and a
 * multi-selection pastes one "title - link" line per work, which is the shape Android joins. Works with no
 * link at all (nothing a browser could open) are dropped instead of contributing an empty line.
 */
internal fun desktopShareText(contents: List<SourceContent>): DesktopShareText {
    val links = contents.mapNotNull { content ->
        val link = content.publicUrl.ifBlank { content.url }.trim()
        if (link.isEmpty()) null else content to link
    }
    return when (links.size) {
        0 -> DesktopShareText("", 0)
        1 -> {
            val (content, link) = links.single()
            DesktopShareText("${content.title}\n \n$link", 1)
        }
        else -> DesktopShareText(
            links.joinToString("\n") { (content, link) -> "${content.title} - $link" },
            links.size,
        )
    }
}

/**
 * Windows has no share sheet, so the clipboard is where Android's SHARE intent lands. The AWT clipboard can
 * report a failure after the content already landed (it races the process that owns the clipboard), so the
 * read-back decides whether the user really has the links; only a clipboard that stays empty is a failure.
 */
internal object DesktopClipboard {
    fun write(text: String): Boolean {
        val written = runCatching {
            Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null)
        }.isSuccess
        return written || read() == text
    }

    fun read(): String? = runCatching {
        Toolkit.getDefaultToolkit().systemClipboard.getData(DataFlavor.stringFlavor) as? String
    }.getOrNull()
}
