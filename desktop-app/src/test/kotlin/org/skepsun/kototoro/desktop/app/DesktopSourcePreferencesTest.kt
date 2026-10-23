package org.skepsun.kototoro.desktop.app

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.core.source.*

/**
 * The protocol tree arrives flat-ish: sources report `PreferenceCategory`s as titled GROUP nodes, mix loose rows in
 * between and let the host append its own `host:` rows at the end. Android folds that into sections before drawing;
 * these cases pin the same folding for the Windows screen.
 */
class DesktopSourcePreferencesTest {
    private fun node(id: String, kind: SourcePreferenceKind, title: String = "", visible: Boolean = true,
        children: List<SourcePreferenceNode> = emptyList(), choices: List<SourcePreferenceChoice> = emptyList(),
        value: SourcePreferenceValue? = null) = SourcePreferenceNode(id = id, key = id, title = title, summary = "",
            kind = kind, enabled = true, visible = visible, value = value, choices = choices, children = children)

    @Test
    fun `host rows lead the default section while a titled group keeps its own`() {
        val sections = sourcePreferenceSections(listOf(
            node("source:choice", SourcePreferenceKind.CHOICE, title = "Mirror",
                choices = listOf(SourcePreferenceChoice("A", "a")), value = SourcePreferenceValue.Text("a")),
            node("group:general", SourcePreferenceKind.GROUP, title = "General",
                children = listOf(node("source:toggle", SourcePreferenceKind.TOGGLE, title = "Adult"))),
            // The host appends its own rows after the source's, exactly as HostUserAgent does.
            node("host:user_agent", SourcePreferenceKind.TEXT, title = "User-Agent"),
            node("host:domain", SourcePreferenceKind.TEXT, title = "Domain"),
        ), "Settings")
        assertEquals(listOf("Settings", "General"), sections.map { it.title })
        assertEquals(listOf("host:user_agent", "host:domain", "source:choice"), sections[0].rows.map { it.id })
        assertEquals(listOf("source:toggle"), sections[1].rows.map { it.id })
    }

    @Test
    fun `untitled groups flatten into the section around them`() {
        val sections = sourcePreferenceSections(listOf(
            node("group:blank", SourcePreferenceKind.GROUP, children = listOf(
                node("source:a", SourcePreferenceKind.TOGGLE), node("source:b", SourcePreferenceKind.TEXT))),
            node("group:titled", SourcePreferenceKind.GROUP, title = "Advanced",
                children = listOf(node("source:c", SourcePreferenceKind.CHOICE))),
            node("source:d", SourcePreferenceKind.ACTION),
        ), "Settings")
        assertEquals(listOf("Settings", "Advanced", "Settings"), sections.map { it.title })
        assertEquals(listOf("source:a", "source:b"), sections[0].rows.map { it.id })
        assertEquals(listOf("source:c"), sections[1].rows.map { it.id })
        assertEquals(listOf("source:d"), sections[2].rows.map { it.id })
        assertEquals(sections.size, sections.map { it.id }.toSet().size)
    }

    @Test
    fun `nested groups flatten into the section that owns them`() {
        val sections = sourcePreferenceSections(listOf(
            node("group:outer", SourcePreferenceKind.GROUP, title = "Outer", children = listOf(
                node("source:a", SourcePreferenceKind.TOGGLE),
                node("group:inner", SourcePreferenceKind.GROUP, title = "Inner",
                    children = listOf(node("source:b", SourcePreferenceKind.TEXT))))),
        ), "Settings")
        assertEquals(listOf("Outer"), sections.map { it.title })
        assertEquals(listOf("source:a", "source:b"), sections.single().rows.map { it.id })
    }

    @Test
    fun `invisible rows and groups without visible rows drop out`() {
        val sections = sourcePreferenceSections(listOf(
            node("host:user_agent", SourcePreferenceKind.TEXT, visible = false),
            node("group:empty", SourcePreferenceKind.GROUP, title = "Empty",
                children = listOf(node("source:hidden", SourcePreferenceKind.TOGGLE, visible = false))),
            node("group:shown", SourcePreferenceKind.GROUP, title = "Shown",
                children = listOf(node("source:switch", SourcePreferenceKind.TOGGLE))),
        ), "Settings")
        assertEquals(listOf("Shown"), sections.map { it.title })
        assertTrue(sourcePreferenceSections(emptyList(), "Settings").isEmpty())
    }
}
