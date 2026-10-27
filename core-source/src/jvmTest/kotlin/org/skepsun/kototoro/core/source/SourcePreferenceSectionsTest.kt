package org.skepsun.kototoro.core.source

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The protocol tree arrives flat-ish: sources report `PreferenceCategory`s as titled GROUP nodes, mix loose rows in
 * between and let the host append its own `host:` rows. Android folds that into sections before drawing; the shared
 * folding therefore lives next to the DTOs so every host that draws the tree gets the same sections.
 */
class SourcePreferenceSectionsTest {
    private fun node(id: String, kind: SourcePreferenceKind, title: String = "", visible: Boolean = true,
        children: List<SourcePreferenceNode> = emptyList(), choices: List<SourcePreferenceChoice> = emptyList(),
        value: SourcePreferenceValue? = null, secret: Boolean = false, summary: String = "") =
        SourcePreferenceNode(id = id, key = id, title = title, summary = summary,
            kind = kind, enabled = true, visible = visible, value = value, choices = choices, children = children,
            isSecret = secret)

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
    fun `nested groups keep their title as a label row inside the owning section`() {
        val sections = sourcePreferenceSections(listOf(
            node("group:outer", SourcePreferenceKind.GROUP, title = "Outer", children = listOf(
                node("source:a", SourcePreferenceKind.TOGGLE),
                node("group:inner", SourcePreferenceKind.GROUP, title = "Inner",
                    children = listOf(node("source:b", SourcePreferenceKind.TEXT))))),
        ), "Settings")
        assertEquals(listOf("Outer"), sections.map { it.title })
        // Android's ComposePreferenceAdapter emits a group-label row before a nested group's preferences.
        val entries = sections.single().entries
        assertEquals(listOf("source:a", "label:group:inner", "source:b"), entries.map { it.id })
        assertEquals(listOf("Inner"), entries.filterIsInstance<SourcePreferenceEntry.Label>().map { it.title })
        assertEquals(listOf("source:a", "source:b"), sections.single().rows.map { it.id })
    }

    @Test
    fun `an untitled nested group only contributes its rows`() {
        val sections = sourcePreferenceSections(listOf(
            node("group:outer", SourcePreferenceKind.GROUP, title = "Outer", children = listOf(
                node("group:blank", SourcePreferenceKind.GROUP, children = listOf(
                    node("source:a", SourcePreferenceKind.TOGGLE))),
                node("group:inner", SourcePreferenceKind.GROUP, title = "Inner", children = listOf(
                    node("group:deeper", SourcePreferenceKind.GROUP, title = "Deeper", children = listOf(
                        node("source:b", SourcePreferenceKind.CHOICE))))),
            )),
        ), "Settings")
        assertEquals(
            listOf("source:a", "label:group:inner", "label:group:deeper", "source:b"),
            sections.single().entries.map { it.id },
        )
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

    @Test
    fun `a row's title falls back to its key and value labels follow Android`() {
        val titles = node("source:untitled", SourcePreferenceKind.TEXT)
        assertEquals("source:untitled", sourcePreferenceRowTitle(titles))
        assertEquals("User-Agent", sourcePreferenceRowTitle(node("host:user_agent", SourcePreferenceKind.TEXT,
            title = "User-Agent")))

        val choice = node("source:mirror", SourcePreferenceKind.CHOICE,
            choices = listOf(SourcePreferenceChoice("Mirror A", "a"), SourcePreferenceChoice("Mirror B", "b")),
            value = SourcePreferenceValue.Text("b"))
        // Android prints the entry's label, not the stored value.
        assertEquals("Mirror B", sourcePreferenceChoiceLabel(choice))
        assertEquals("Mirror B", sourcePreferenceValueLabel(choice))

        val custom = node("source:mirror", SourcePreferenceKind.CHOICE,
            choices = listOf(SourcePreferenceChoice("Mirror A", "a")), value = SourcePreferenceValue.Text("custom"))
        // A custom mirror is not in the list, so its raw value is the only name it has.
        assertEquals("custom", sourcePreferenceChoiceLabel(custom))
        assertNull(sourcePreferenceChoiceLabel(node("source:mirror", SourcePreferenceKind.CHOICE)))

        val multi = node("source:kinds", SourcePreferenceKind.MULTI_CHOICE,
            choices = listOf(SourcePreferenceChoice("Manga", "m"), SourcePreferenceChoice("Novel", "n"),
                SourcePreferenceChoice("Video", "v")),
            value = SourcePreferenceValue.TextSet(setOf("v", "m")))
        assertEquals("Manga, Video", sourcePreferenceMultiChoiceLabel(multi))
        assertNull(sourcePreferenceMultiChoiceLabel(node("source:kinds", SourcePreferenceKind.MULTI_CHOICE)))
        // A set of values the source no longer offers still shows how many are selected.
        val stale = node("source:kinds", SourcePreferenceKind.MULTI_CHOICE,
            value = SourcePreferenceValue.TextSet(setOf("x", "y")))
        assertEquals("2", sourcePreferenceMultiChoiceLabel(stale))

        assertEquals("secret", sourcePreferenceTextLabel(node("source:token", SourcePreferenceKind.TEXT,
            value = SourcePreferenceValue.Text("secret"))))
        assertEquals("••••••", sourcePreferenceTextLabel(node("source:token", SourcePreferenceKind.TEXT,
            value = SourcePreferenceValue.Text("secret"), secret = true)))
        // Twelve bullets at most, so a long secret does not widen the row.
        assertEquals("•".repeat(12), sourcePreferenceTextLabel(node("source:token", SourcePreferenceKind.TEXT,
            value = SourcePreferenceValue.Text("x".repeat(40)), secret = true)))
        assertNull(sourcePreferenceTextLabel(node("source:token", SourcePreferenceKind.TEXT)))
        assertNull(sourcePreferenceValueLabel(node("source:login", SourcePreferenceKind.ACTION)))
        assertNull(sourcePreferenceValueLabel(node("source:about", SourcePreferenceKind.INFO)))
    }
}
