package org.skepsun.kototoro.desktop.app

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.desktop.runtime.DesktopJsonSource

class DesktopImportedRepositoriesTest {
    @Test
    fun `sources from one TVBox configuration share a repository and retain other origins`() {
        fun source(id: String, address: String) = DesktopJsonSource(id, id,
            """{"site":{"name":"$id","type":1,"api":"https://site.test/$id"},
                "meta":{"sourceLocator":"$address","sourceTitle":"Repository"}}""")
        val first = source("one", "https://repo.test/first.json")
        val second = source("two", "https://repo.test/first.json")
        val other = source("three", "https://repo.test/other.json")
        val groups = importedSourceRepositories(listOf(first, second, other))
        assertEquals(2, groups.size)
        assertEquals(listOf(first, second), groups[0].sources)
        assertEquals("https://repo.test/first.json", groups[0].address)
        assertEquals("Repository", groups[0].title)
        assertEquals(listOf(other), groups[1].sources)
    }

    @Test
    fun `a malformed stored configuration remains visible for removal`() {
        val source = DesktopJsonSource("broken", "Broken configuration", "invalid json")
        val repository = importedSourceRepositories(listOf(source)).single()
        assertEquals(source.name, repository.title)
        assertEquals(listOf(source), repository.sources)
    }
}
