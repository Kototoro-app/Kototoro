package org.skepsun.kototoro.core.source

import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.mihon.MihonFilterMapper
import org.skepsun.kototoro.parsers.model.ContentListFilter
import org.skepsun.kototoro.parsers.model.ContentTag
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import sun.misc.Unsafe

class MihonFilterMapperCompatibilityTest {
    @Test
    fun `unknown binary filter subclasses are skipped without crashing sealed dispatch`() {
        // MockK substitutes a known sealed child, so it cannot exercise the external-binary fallback.
        // Emit a constructor-free JVM 11 subtype; this checks dispatch, not extension construction compatibility.
        val output = ByteArrayOutputStream()
        DataOutputStream(output).use {
            it.writeInt(0xCAFEBABE.toInt()); it.writeShort(0); it.writeShort(55); it.writeShort(5)
            it.writeByte(1); it.writeUTF("fixture/UnknownFilter")
            it.writeByte(7); it.writeShort(1)
            it.writeByte(1); it.writeUTF("eu/kanade/tachiyomi/source/model/Filter")
            it.writeByte(7); it.writeShort(3)
            it.writeShort(0x21); it.writeShort(2); it.writeShort(4)
            repeat(4) { _ -> it.writeShort(0) } // interfaces, fields, methods, attributes
        }
        val bytes = output.toByteArray()
        val loader = object : ClassLoader(Filter::class.java.classLoader) {
            fun subtype() = defineClass(null, bytes, 0, bytes.size)
        }
        val allocator = Unsafe::class.java.getDeclaredField("theUnsafe").let {
            it.isAccessible = true
            it.get(null) as Unsafe
        }
        val custom = allocator.allocateInstance(loader.subtype()) as Filter<*>
        Filter::class.java.getDeclaredField("name").also { it.isAccessible = true }.set(custom, "Custom")
        assertTrue(MihonFilterMapper.mapOptions(FilterList(custom), protocolTestSource).availableTags.isEmpty())
        MihonFilterMapper.updateMihonFilters(FilterList(custom), ContentListFilter())
    }
    private val flag = object : Filter.CheckBox("Flag", true) {}
    private val genre = object : Filter.TriState("Genre") {}
    private val choice = object : Filter.Select<String>("Choice",
        arrayOf("ThemeInfo(name=爱情, key=a)", "key=fragment)", "Second")) {}
    private val order = object : Filter.Sort("Order", arrayOf("Name", "Date"), Filter.Sort.Selection(0, true)) {}
    private val author = object : Filter.Text("Author", "default") {}
    private val extra = object : Filter.Text("AuthorExtra", "extra") {}
    private val nested = object : Filter.CheckBox("NestedFlag") {}
    private val group = object : Filter.Group<Filter<*>>("Group", listOf(
        object : Filter.Group<Filter<*>>("Nested", listOf(nested)) {})) {}
    private val filters = FilterList(Filter.Header("General"), flag, genre, choice, order, author, extra, group)
    private fun tag(key: String) = ContentTag(key, key, protocolTestSource)

    @Test
    fun `Android options retain persisted tag keys cleaned choices and grouping`() {
        val options = MihonFilterMapper.mapOptions(filters, protocolTestSource)
        assertEquals(listOf("General", "Group"), options.tagGroups.map { it.title })
        assertEquals(setOf("top:Flag", "top:Genre", "top:Choice/爱情", "top:Choice/Second", "sort:top:Order/Name",
            "sort:top:Order/Date", "text:top:Author", "text:top:AuthorExtra", "Group/Nested/NestedFlag"),
            options.availableTags.map { it.key }.toSet())
        assertTrue(options.availableTags.all { it.source === protocolTestSource })
    }

    @Test
    fun `Android state adapter applies legacy tags to original subclasses and native indices`() {
        MihonFilterMapper.updateMihonFilters(filters, ContentListFilter(tags = setOf(tag("top:Choice/Second"),
            tag("sort:top:Order/Date"), tag("Group/Nested/NestedFlag")), tagsExclude = setOf(tag("top:Genre"))))
        assertFalse(flag.state)
        assertEquals(Filter.TriState.STATE_EXCLUDE, genre.state)
        assertEquals(2, choice.state)
        assertEquals(Filter.Sort.Selection(1, true), order.state)
        assertTrue(nested.state)
    }

    @Test
    fun `text prefix collisions no longer overwrite sibling control state`() {
        MihonFilterMapper.updateMihonFilters(filters,
            ContentListFilter(tags = setOf(tag("text:top:AuthorExtra=中文=+&"))))
        assertEquals("default", author.state)
        assertEquals("中文=+&", extra.state)
    }

    @Test
    fun `legacy defaults and permissive stale keys retain existing Android behavior`() {
        MihonFilterMapper.updateMihonFilters(filters, ContentListFilter(tags = setOf(tag("stale"))))
        assertFalse(flag.state)
        assertEquals(Filter.TriState.STATE_IGNORE, genre.state)
        assertEquals(0, choice.state)
        assertEquals(Filter.Sort.Selection(0, true), order.state)
        assertEquals("default", author.state)
    }
}
