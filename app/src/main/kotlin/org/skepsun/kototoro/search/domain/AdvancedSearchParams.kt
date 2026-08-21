package org.skepsun.kototoro.search.domain

data class AdvancedSearchParams(
        val query: String = "",
        val title: String = "",
        val tags: String = "",
        val author: String = "",
    )