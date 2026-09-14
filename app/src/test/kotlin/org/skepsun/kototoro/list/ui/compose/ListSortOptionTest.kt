package org.skepsun.kototoro.list.ui.compose

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.skepsun.kototoro.list.domain.ListSortOrder

class ListSortOptionTest : FunSpec({
    test("favorites exposes seven criteria while preserving every saved order") {
        val options = listSortOptions(ListSortOrder.FAVORITES.toList())
        options.size shouldBe 7
        ListSortOrder.FAVORITES.forEach { order ->
            val option = options.single { it.contains(order) }
            option.orderFor(option.descending == order) shouldBe order
        }
    }

    test("changing criterion keeps direction when supported") {
        val options = listSortOptions(ListSortOrder.FAVORITES.toList())
        options.single { it.contains(ListSortOrder.NEWEST) }.orderFor(false) shouldBe ListSortOrder.OLDEST
        options.single { it.contains(ListSortOrder.PROGRESS) }.orderFor(false) shouldBe ListSortOrder.UNREAD
        options.single { it.contains(ListSortOrder.LAST_READ) }.orderFor(false) shouldBe ListSortOrder.LONG_AGO_READ
        options.single { it.contains(ListSortOrder.ALPHABETIC) }.orderFor(true) shouldBe
            ListSortOrder.ALPHABETIC_REVERSE
    }

    test("single direction criteria fall back to their supported order") {
        val options = listSortOptions(ListSortOrder.FAVORITES.toList())
        options.single { it.contains(ListSortOrder.RATING) }.orderFor(false) shouldBe ListSortOrder.RATING
        options.single { it.contains(ListSortOrder.UPDATED) }.orderFor(false) shouldBe ListSortOrder.UPDATED
    }

    test("restricted lists do not introduce unavailable orders") {
        val options = listSortOptions(listOf(ListSortOrder.ALPHABETIC_REVERSE, ListSortOrder.RELEVANCE))
        options.size shouldBe 2
        options.first().ascending shouldBe null
        options.first().orderFor(false) shouldBe ListSortOrder.ALPHABETIC_REVERSE
        listSortOptions(emptyList()) shouldBe emptyList()
    }
})
