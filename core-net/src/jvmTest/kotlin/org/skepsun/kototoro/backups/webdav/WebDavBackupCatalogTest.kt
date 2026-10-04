package org.skepsun.kototoro.backups.webdav

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.backups.ui.periodical.RemoteNamespace

class WebDavBackupCatalogTest {
    private fun classify(vararg names: String) = WebDavBackupCatalog.classify(
        names.mapIndexed { index, name -> WebDavResource(name, index.toLong(), index.toLong()) },
    )

    @Test
    fun `generations retain prefixes versions and writer generations`() {
        val files = classify("kototoro-v7-a.zip", "kototoro-data-v8-a.zip", "kototoro-v2-data-v9-a.zip",
            "kototoro-v3-work-v10-a.zip")
        assertEquals(listOf(10, 9, 8, 7), files.map { it.dataVersion })
        assertEquals(listOf(3, 2, 1, 1), files.map { it.writerGeneration })
    }

    @Test
    fun `zip extension ignores case but generation prefixes remain case sensitive`() {
        val files = classify("KOTOTORO-v3-work-v8-a.ZIP", "other-v2-a.zip", "kototoro-v3-work-v8-a.txt", "plain.zip")
        assertEquals(3, files.size)
        assertEquals(listOf(null, 2, 3), files.map { it.dataVersion })
        assertEquals(listOf(RemoteNamespace.V1), files.map { it.namespace }.distinct())
    }

    @Test
    fun `strict version overflow does not fall back to another version`() {
        val files = classify("kototoro-v999999999999-v7-a.zip", "kototoro-v2-data-vbad-v8-a.zip",
            "kototoro-v3-work-vbad-v8-a.zip", "legacy-v8-a.zip")
        assertEquals(8, files.first().dataVersion)
        assertEquals(listOf(null, null, null), files.drop(1).map { it.dataVersion })
    }

    @Test
    fun `timestamps descend and equal timestamps preserve response order`() {
        val files = WebDavBackupCatalog.classify(
            listOf(WebDavResource("a.zip", 0, -1), WebDavResource("b.zip", 5, 2),
                WebDavResource("c.zip", 5, 3), WebDavResource("d.zip", -1, 4)),
        )
        assertEquals(listOf("b.zip", "c.zip", "a.zip", "d.zip"), files.map { it.name })
        assertEquals(-1L, files[2].size)
    }

    @Test
    fun `latest prefers generation before date and handles fallback`() {
        val files = classify("kototoro-v3-work-v8-old.zip", "kototoro-v2-data-v7-new.zip", "kototoro-v9-newest.zip")
        assertEquals(RemoteNamespace.V3, WebDavBackupCatalog.latest(files)?.namespace)
        assertEquals(
            RemoteNamespace.V2,
            WebDavBackupCatalog.latest(files.filter { it.namespace != RemoteNamespace.V3 })?.namespace,
        )
        assertNull(WebDavBackupCatalog.latest(emptyList()))
    }

    @Test
    fun `retention only removes excess files from selected generation`() {
        val files = classify("kototoro-v3-work-v8-old.zip", "kototoro-v3-work-v8-new.zip", "kototoro-v2-data-v7-a.zip")
        assertEquals(listOf("kototoro-v3-work-v8-old.zip"),
            WebDavBackupCatalog.retired(files, 1, RemoteNamespace.V3).map { it.name })
        for (limit in listOf(-1, 0, 2, 9)) {
            assertEquals(emptyList<WebDavBackupFile>(), WebDavBackupCatalog.retired(files, limit, RemoteNamespace.V3))
        }
    }

    @Test
    fun `remote names preserve platform formatted timestamp`() {
        assertEquals(listOf("kototoro-v8-20261002-231500.zip", "kototoro-v2-data-v8-20261002-231500.zip",
            "kototoro-v3-work-v8-20261002-231500.zip"),
            RemoteNamespace.entries.map { WebDavBackupCatalog.remoteName(8, it, "20261002-231500") })
    }

    @Test
    fun `endpoint preserves shared directory and percent encoded names`() {
        val endpoint = WebDavEndpoint("https://fixture.test/base///", "/backup/path/")
        assertEquals("https://fixture.test/base/backup/path/", endpoint.url())
        assertEquals("https://fixture.test/base/backup/path/a%20b.zip", endpoint.url("a%20b.zip"))
        assertEquals("https://fixture.test/", WebDavEndpoint("https://fixture.test/", "").url())
    }
}
