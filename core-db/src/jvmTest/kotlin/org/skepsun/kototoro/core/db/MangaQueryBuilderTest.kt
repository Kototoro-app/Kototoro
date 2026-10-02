package org.skepsun.kototoro.core.db

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

/**
 * The query builder is the only place where [ListFilterCriteria] meets SQL: criteria that share a group key are
 * OR-ed, different groups are AND-ed, Inverted wraps its option in NOT(), and a criterion the DAO cannot express
 * must fail loudly instead of silently dropping the filter.
 */
class MangaQueryBuilderTest {

    private val callback = MangaQueryBuilder.ConditionCallback { option ->
        when (option) {
            is ListFilterCriteria.Tag -> "tag_id = ${option.tagId}"
            is ListFilterCriteria.Favorite -> "category_id = ${option.categoryId}"
            ListFilterCriteria.Macro.NSFW -> "nsfw = 1"
            else -> null
        }
    }

    private fun sql(vararg options: ListFilterCriteria) =
        MangaQueryBuilder("suggestions", callback).filters(options.toList()).build().sql

    @Test
    fun `no filters selects the whole table`() {
        assertEquals("SELECT * FROM suggestions", sql())
    }

    @Test
    fun `options of the same group are OR-ed`() {
        assertEquals(
            "SELECT * FROM suggestions WHERE (tag_id = 1 OR tag_id = 2)",
            sql(ListFilterCriteria.Tag(1), ListFilterCriteria.Tag(2)),
        )
    }

    @Test
    fun `different groups are AND-ed and a single option is not parenthesised`() {
        assertEquals(
            "SELECT * FROM suggestions WHERE tag_id = 7 AND nsfw = 1",
            sql(ListFilterCriteria.Tag(7), ListFilterCriteria.Macro.NSFW),
        )
    }

    @Test
    fun `inverted option is wrapped in NOT and keeps its own group`() {
        assertEquals(
            "SELECT * FROM suggestions WHERE NOT(nsfw = 1) AND category_id = 3",
            sql(ListFilterCriteria.Inverted(ListFilterCriteria.Macro.NSFW), ListFilterCriteria.Favorite(3)),
        )
    }

    @Test
    fun `where, order, group and limit are appended in SQL order`() {
        val query = MangaQueryBuilder("tracks", callback)
            .where("a = 1").filters(listOf(ListFilterCriteria.Tag(5)))
            .groupBy("manga_id").orderBy("created_at DESC").limit(20).build().sql
        assertEquals("SELECT * FROM tracks WHERE a = 1 AND tag_id = 5 GROUP BY manga_id ORDER BY created_at DESC LIMIT 20", query)
    }

    @Test
    fun `an option the DAO cannot express fails instead of being dropped`() {
        assertThrows(IllegalArgumentException::class.java) { sql(ListFilterCriteria.Downloaded) }
    }
}
