package org.skepsun.kototoro.core.source

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class MihonFilterRulesTest {
    private val source = SourceRef("MIHON_42", "zh", "MANGA")
    private val toggle = SourceFilterNode("toggle", "Flag", SourceFilterKind.CHECKBOX, SourceFilterValue.Toggle(true))
    private val tri = SourceFilterNode("tri", "Genre", SourceFilterKind.TRISTATE,
        SourceFilterValue.TriState(SourceTriState.IGNORE))
    private val choice = SourceFilterNode("choice", "Choice", SourceFilterKind.SELECT, SourceFilterValue.Choice(0),
        listOf("ThemeInfo(name=爱情, key=a)", "key=fragment)", "Second"))
    private val sort = SourceFilterNode("sort", "Order", SourceFilterKind.SORT, SourceFilterValue.Sort(0, true),
        listOf("Name", "Date"))
    private val text = SourceFilterNode("text", "Author", SourceFilterKind.TEXT, SourceFilterValue.Text("default"))
    private val extra = SourceFilterNode("extra", "AuthorExtra", SourceFilterKind.TEXT, SourceFilterValue.Text("extra"))
    private val nodes = listOf(toggle, tri, choice, sort, text, extra)

    @Test
    fun `node identity distinguishes duplicate labels paths and stale kinds`() {
        assertNotEquals(MihonFilterRules.nodeId(listOf(0), SourceFilterKind.TEXT, "name"),
            MihonFilterRules.nodeId(listOf(1), SourceFilterKind.TEXT, "name"))
        assertNotEquals(MihonFilterRules.nodeId(listOf(0), SourceFilterKind.TEXT, "name"),
            MihonFilterRules.nodeId(listOf(0), SourceFilterKind.CHECKBOX, "name"))
        assertEquals("0.1|TEXT|作者", MihonFilterRules.nodeId(listOf(0, 1), SourceFilterKind.TEXT, "作者"))
    }

    @Test
    fun `tag projection preserves existing group keys merges headers and discards genre fragments`() {
        val tree = listOf(SourceFilterNode("h", "General", SourceFilterKind.HEADER), toggle,
            SourceFilterNode("g", "Group", SourceFilterKind.GROUP, children = listOf(choice, sort, tri)),
            SourceFilterNode("h2", "General", SourceFilterKind.HEADER), text,
            SourceFilterNode("sep", "", SourceFilterKind.SEPARATOR))
        val options = MihonFilterRules.options(tree, source)
        assertEquals(listOf("General", "Group - Choice", "Group - Order", "Group"), options.tagGroups.map { it.title })
        assertEquals(setOf("top:Flag", "Group/Choice/爱情", "Group/Choice/Second", "sort:Group/Order/Name",
            "sort:Group/Order/Date", "Group/Genre", "text:top:Author"), options.availableTags.map { it.key }.toSet())
        assertFalse(options.availableTags.any { it.title == "key=fragment)" })
    }

    @Test
    fun `legacy tags resolve real indexes and preserve default sort direction`() {
        val changes = MihonFilterRules.changes(nodes, setOf("top:Choice/Second", "sort:top:Order/Date"),
            setOf("top:Genre"), strict = true).associate { it.id to it.value }
        assertEquals(SourceFilterValue.Choice(2), changes["choice"])
        assertEquals(SourceFilterValue.Sort(1, true), changes["sort"])
        assertEquals(SourceFilterValue.Toggle(false), changes["toggle"])
        assertEquals(SourceFilterValue.TriState(SourceTriState.EXCLUDE), changes["tri"])
    }

    @Test
    fun `text tags match whole control names and preserve equals in text values`() {
        val changes = MihonFilterRules.changes(nodes, setOf("text:top:AuthorExtra=名称=值+&"), emptySet(), strict = true)
        assertFalse(changes.any { it.id == "text" })
        assertEquals(SourceFilterValue.Text("名称=值+&"), changes.single { it.id == "extra" }.value)
    }

    @Test
    fun `nested group tag paths match the original recursive Android paths`() {
        val group = SourceFilterNode("g", "Group", SourceFilterKind.GROUP, children = listOf(
            SourceFilterNode("nested", "Nested", SourceFilterKind.GROUP, children = listOf(toggle))))
        val key = MihonFilterRules.options(listOf(group), source).availableTags.single().key
        assertEquals("Group/Nested/Flag", key)
        assertEquals(SourceFilterValue.Toggle(true),
            MihonFilterRules.changes(listOf(group), setOf(key), emptySet(), strict = true).single().value)
    }

    @Test
    fun `dynamic changes preserve unspecified defaults and encode all native states through JSON`() {
        val dynamic = listOf(SourceFilterChange("sort", SourceFilterValue.Sort(1, false)),
            SourceFilterChange("choice", SourceFilterValue.Choice(1)), SourceFilterChange("text", SourceFilterValue.Text("中文=+&")),
            SourceFilterChange("tri", SourceFilterValue.TriState(SourceTriState.INCLUDE)),
            SourceFilterChange("toggle", SourceFilterValue.Toggle(false)))
        assertEquals(dynamic, MihonFilterRules.changes(nodes, emptySet(), emptySet(), dynamic,
            strict = true, applyLegacy = false))
        val filter = SourceFilter(dynamicFilters = dynamic)
        assertEquals(filter, SourceProtocolJson.decodeFromString<SourceFilter>(SourceProtocolJson.encodeToString(filter)))
        val definition = SourceDynamicFilters(source, nodes)
        assertEquals(definition, SourceProtocolJson.decodeFromString<SourceDynamicFilters>(SourceProtocolJson.encodeToString(definition)))
    }

    @Test
    fun `sort can be cleared while nonnullable controls require their own value type`() {
        assertEquals(SourceFilterChange("sort", null), MihonFilterRules.changes(nodes, emptySet(), emptySet(),
            listOf(SourceFilterChange("sort", null)), applyLegacy = false).single())
        val invalid = listOf(SourceFilterChange("text", null), SourceFilterChange("toggle", SourceFilterValue.Choice(0)),
            SourceFilterChange("choice", SourceFilterValue.Toggle(true)), SourceFilterChange("tri", SourceFilterValue.Text("x")))
        invalid.forEach { change ->
            assertThrows(SourceInvalidArgumentException::class.java) {
                MihonFilterRules.changes(nodes, emptySet(), emptySet(), listOf(change))
            }
        }
    }

    @Test
    fun `stale unknown duplicate and out of range dynamic changes are rejected before writes`() {
        val invalid = listOf(listOf(SourceFilterChange("stale", SourceFilterValue.Text("x"))),
            listOf(SourceFilterChange("choice", SourceFilterValue.Choice(-1))),
            listOf(SourceFilterChange("sort", SourceFilterValue.Sort(2, true))),
            listOf(SourceFilterChange("text", SourceFilterValue.Text("a")), SourceFilterChange("text", SourceFilterValue.Text("b"))))
        for (changes in invalid) assertThrows(SourceInvalidArgumentException::class.java) {
            MihonFilterRules.changes(nodes, emptySet(), emptySet(), changes)
        }
        val unsupported = SourceFilterNode("unknown", "Custom", SourceFilterKind.UNSUPPORTED)
        assertThrows(SourceInvalidArgumentException::class.java) {
            MihonFilterRules.changes(listOf(unsupported), emptySet(), emptySet(),
                listOf(SourceFilterChange("unknown", SourceFilterValue.Text("x"))))
        }
    }

    @Test
    fun `strict host validation rejects unconsumed exclusions and contradictory tags while legacy remains permissive`() {
        assertThrows(SourceInvalidArgumentException::class.java) {
            MihonFilterRules.changes(nodes, setOf("missing"), emptySet(), strict = true)
        }
        assertThrows(SourceInvalidArgumentException::class.java) {
            MihonFilterRules.changes(nodes, emptySet(), setOf("top:Flag"), strict = true)
        }
        assertThrows(SourceInvalidArgumentException::class.java) {
            MihonFilterRules.changes(nodes, setOf("top:Genre"), setOf("top:Genre"), strict = true)
        }
        assertTrue(MihonFilterRules.changes(nodes, setOf("missing"), emptySet()).isNotEmpty())
    }
}
