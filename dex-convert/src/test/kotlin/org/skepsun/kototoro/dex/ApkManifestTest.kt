package org.skepsun.kototoro.dex

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ApkManifestTest {
    @TempDir lateinit var root: Path

    private val manifest = """
        <?xml version="1.0" encoding="utf-8"?>
        <manifest xmlns:android="http://schemas.android.com/apk/res/android"
            package="eu.kanade.tachiyomi.animeextension.en.sample" android:versionCode="1410" android:versionName="14.10">
            <uses-feature android:name="tachiyomi.animeextension" />
            <application android:label="Aniyomi: 示例 &amp; Sample">
                <meta-data android:name="tachiyomi.animeextension.class" android:value=".Sample;.Other" />
                <meta-data android:name="tachiyomi.animeextension.nsfw" android:value="1" />
                <meta-data android:name="tachiyomix.extensionLib" android:value="1.6" />
                <meta-data android:name="flag" android:value="true" />
            </application>
        </manifest>
    """.trimIndent()

    private fun apk(): Path {
        val tools = AndroidTools.find()
        assumeTrue(tools != null, "Android SDK with aapt2 is required")
        val dex = root.resolve("classes.dex").also { Files.write(it, ByteArray(8)) }
        return tools!!.apk(root, manifest, dex)
    }

    @Test
    fun `the binary manifest of a real aapt2 built apk is decoded`() {
        val result = ApkManifestReader.read(apk())
        assertEquals("eu.kanade.tachiyomi.animeextension.en.sample", result.packageName)
        assertEquals(1410L, result.versionCode)
        assertEquals("14.10", result.versionName)
        assertEquals("Aniyomi: 示例 & Sample", result.label)
        assertEquals(setOf("tachiyomi.animeextension"), result.features)
        assertEquals(".Sample;.Other", result.metaData["tachiyomi.animeextension.class"])
        // Integers, floats and booleans keep their natural text so the host's text rules apply unchanged.
        assertEquals("1", result.metaData["tachiyomi.animeextension.nsfw"])
        assertEquals("1.6", result.metaData["tachiyomix.extensionLib"])
        assertEquals("true", result.metaData["flag"])
    }

    @Test
    fun `the decoded manifest renders as the text manifest the inspectors read`() {
        val text = ApkManifestReader.read(apk()).toText()
        assertTrue(text.contains("""package="eu.kanade.tachiyomi.animeextension.en.sample""""))
        assertTrue(text.contains("""android:versionCode="1410""""))
        assertTrue(text.contains("""<uses-feature android:name="tachiyomi.animeextension"/>"""))
        assertTrue(text.contains("""android:name="tachiyomi.animeextension.class" android:value=".Sample;.Other""""))
        assertTrue(text.contains("Aniyomi: 示例 &amp; Sample"))
        // The rendering is well-formed XML.
        javax.xml.parsers.DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(text.byteInputStream())
    }

    @Test
    fun `malformed input is reported instead of crashing`() {
        assertThrows<ApkManifestException> { ApkManifestReader.parse(ByteArray(0)) }
        assertThrows<ApkManifestException> { ApkManifestReader.parse(ByteArray(64) { 1 }) }
        val truncated = Files.readAllBytes(apk().let { path ->
            java.util.zip.ZipFile(path.toFile()).use { zip -> root.resolve("axml.bin").also { Files.write(it, zip.getInputStream(zip.getEntry("AndroidManifest.xml")).readAllBytes()) } }
        })
        assertThrows<ApkManifestException> { ApkManifestReader.parse(truncated.copyOf(truncated.size / 2)) }
        val notAnApk = root.resolve("plain.zip").also { path ->
            ZipOutputStream(Files.newOutputStream(path)).use { it.putNextEntry(ZipEntry("a.txt")); it.write(1); it.closeEntry() }
        }
        assertFalse(runCatching { ApkManifestReader.read(notAnApk) }.isSuccess)
        assertThrows<ApkManifestException> { ApkManifestReader.read(root.resolve("missing.apk")) }
    }
}
