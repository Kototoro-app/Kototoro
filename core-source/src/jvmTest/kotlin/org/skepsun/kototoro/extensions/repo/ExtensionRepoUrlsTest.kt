package org.skepsun.kototoro.extensions.repo

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertEquals

class ExtensionRepoUrlsTest {
    @Test
    fun indexFileNamesAreNotPartOfTheRepositoryBase() {
        val base = "https://raw.githubusercontent.com/LittleSurvival/copymanga-copy20/repo"
        for (name in listOf("index.min.json", "index.json", "index.pb", "repo.json", "plugins.json")) {
            assertEquals(base, ExtensionRepoUrls.repositoryBase("$base/$name"))
        }
        assertEquals(base, ExtensionRepoUrls.repositoryBase("$base/"))
    }

    @Test
    fun resourcesResolveBesideTheListFile() {
        val list = "https://example.org/repo/index.min.json"
        assertEquals("https://example.org/repo/apk/a.apk", ExtensionRepoUrls.resolveResource(list, "apk/a.apk"))
        assertEquals("https://example.org/repo/apk/a.apk", ExtensionRepoUrls.resolveResource("https://example.org/repo/", "/apk/a.apk"))
        assertEquals("https://cdn.example/a.apk", ExtensionRepoUrls.resolveResource(list, "https://cdn.example/a.apk"))
    }
}
