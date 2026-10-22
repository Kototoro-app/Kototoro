package org.skepsun.kototoro.extensions.runtime

import android.content.pm.PackageInfo
import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class LocalArchivePackageIndexTest {

    @Test
    fun `each archive is parsed once across repeated lookups`(@TempDir dir: File) {
        val archives = listOf("a", "b", "c").map { name ->
            File(dir, "$name.apk").apply { writeText(name) }
        }
        var parseCount = 0
        val index = LocalArchivePackageIndex()

        repeat(5) {
            val entries = index.entries("mihon", archives) { apk ->
                parseCount++
                packageInfo(apk.nameWithoutExtension)
            }
            assertEquals(3, entries.size)
        }

        assertEquals(3, parseCount, "repeated lookups must reuse the parsed archives")
    }

    @Test
    fun `a list of extensions costs one parse per archive instead of one walk per row`(@TempDir dir: File) {
        // The extension list and the onboarding wizard ask "is this package installed locally?" once
        // per repository row, and the signature fallback asks once per package across four ecosystems.
        val managedApks = (1..60).map { index ->
            File(dir, "ext$index.apk").apply { writeText("apk$index") }
        }
        var parseCount = 0
        val index = LocalArchivePackageIndex()
        val parse: (File) -> PackageInfo = { apk ->
            parseCount++
            packageInfo("pkg.${apk.nameWithoutExtension}")
        }

        repeat(300) { row ->
            val entries = index.entries("mihon", managedApks, parse)
            val wanted = "pkg.ext${(row % 60) + 1}"
            assertNotNull(entries.firstOrNull { it.second.packageName == wanted })
        }

        assertEquals(60, parseCount, "300 rows over 60 archives must not re-parse the archives")
    }

    @Test
    fun `unparsable archives are skipped but do not break the index`(@TempDir dir: File) {
        val valid = File(dir, "valid.apk").apply { writeText("valid") }
        val broken = File(dir, "broken.apk").apply { writeText("broken") }
        val index = LocalArchivePackageIndex()

        val entries = index.entries("mihon", listOf(valid, broken)) { apk ->
            when (apk.name) {
                "broken.apk" -> null
                else -> packageInfo("pkg.valid")
            }
        }

        assertEquals(1, entries.size)
        assertEquals("pkg.valid", entries.single().second.packageName)
        assertEquals(valid, entries.single().first)
    }

    @Test
    fun `replacing an archive reparses the directory`(@TempDir dir: File) {
        val archives = listOf("a", "b").map { name ->
            File(dir, "$name.apk").apply { writeText(name) }
        }
        var parseCount = 0
        val index = LocalArchivePackageIndex()
        val parse: (File) -> PackageInfo = { apk ->
            parseCount++
            packageInfo("pkg.${apk.nameWithoutExtension}.${apk.length()}")
        }

        index.entries("mihon", archives, parse)
        assertEquals(2, parseCount)

        archives.first().writeText("replaced-with-longer-content")
        archives.first().setLastModified(archives.first().lastModified() + 1_000)

        val entries = index.entries("mihon", archives, parse)

        assertEquals(4, parseCount, "an updated archive must invalidate the cached metadata")
        assertEquals(
            "pkg.a.${archives.first().length()}",
            entries.first { it.first == archives.first() }.second.packageName,
            "the updated archive must be re-read",
        )
    }

    @Test
    fun `fingerprint ignores the iteration order of the archive list`(@TempDir dir: File) {
        val first = File(dir, "a.apk").apply { writeText("a") }
        val second = File(dir, "b.apk").apply { writeText("b") }

        assertEquals(
            LocalArchivePackageIndex.fingerprintOf(listOf(first, second)),
            LocalArchivePackageIndex.fingerprintOf(listOf(second, first)),
        )
    }

    @Test
    fun `direct candidates cover both managed layouts`(@TempDir dir: File) {
        val candidates = LocalApkExtensionSupport.directApkCandidates(dir, "org.example.ext")

        assertEquals(
            listOf(
                File(File(dir, "org.example.ext"), "org.example.ext.apk"),
                File(dir, "org.example.ext.apk"),
            ),
            candidates,
        )
    }

    private fun packageInfo(packageName: String): PackageInfo = PackageInfo().apply {
        this.packageName = packageName
    }
}
