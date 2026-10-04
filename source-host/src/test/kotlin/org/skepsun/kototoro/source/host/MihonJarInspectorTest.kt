package org.skepsun.kototoro.source.host

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.skepsun.kototoro.core.source.SourceEcosystem
import java.nio.file.Files
import java.nio.file.Path

class MihonJarInspectorTest {
    @TempDir
    lateinit var directory: Path

    @Test
    fun `real archive metadata hash and Java11 ceiling are inspected without executing constructors`() {
        val fixtures = JarFixture(directory)
        val path = fixtures.sourceJar(body = "public Single() { throw new AssertionError(\"must not execute\"); }")
        val metadata = MihonJarInspector(55).inspect(path)
        assertEquals("fixture.extension", metadata.packageName)
        assertEquals("漫画 & fixture", metadata.displayName)
        assertEquals(16L, metadata.versionCode)
        assertEquals("1.6.16", metadata.versionName)
        assertEquals("1.6", metadata.extensionLib)
        assertEquals(listOf("fixture.extension.Single"), metadata.entryClasses)
        assertFalse(metadata.isNsfw)
        assertEquals(55, metadata.maximumClassVersion)
        assertTrue(metadata.sha256.matches(Regex("[0-9a-f]{64}")))
    }

    @Test
    fun `novel manifests are classified as Tsundoku with their own keys and inferred library`() {
        val fixtures = JarFixture(directory)
        val explicit = MihonJarInspector().inspect(fixtures.sourceJar(name = "NovelA", packageName = "fixture.novel", metadata = JarFixture.novelManifest()))
        assertEquals(SourceEcosystem.TSUNDOKU, explicit.ecosystem)
        assertEquals("夹具小说", explicit.displayName)
        assertEquals("1.6", explicit.extensionLib)
        assertEquals(listOf("fixture.novel.NovelSource"), explicit.entryClasses)
        assertFalse(explicit.isNsfw)

        // No tachiyomix.extensionLib: Tsundoku derives it from the version name, as the Android loader does.
        val inferred = MihonJarInspector().inspect(fixtures.sourceJar(
            name = "NovelB", packageName = "fixture.novel",
            metadata = JarFixture.novelManifest(libraryMarker = false, versionName = "1.4.12"),
        ))
        assertEquals("1.4", inferred.extensionLib)

        val warned = MihonJarInspector().inspect(fixtures.sourceJar(
            name = "NovelC", packageName = "fixture.novel",
            metadata = JarFixture.novelManifest(extra = """<meta-data android:name="tachiyomix.contentWarning" android:value="2"/>"""),
        ))
        assertTrue(warned.isNsfw)

        // A manga manifest stays Mihon, and a manifest declaring both entry points is refused.
        assertEquals(
            SourceEcosystem.MIHON,
            MihonJarInspector().inspect(fixtures.sourceJar(name = "MangaA")).ecosystem,
        )
        val both = JarFixture.novelManifest(
            extra = """<meta-data android:name="tachiyomi.extension.class" android:value=".Single"/>""",
        )
        assertEquals(SourceJarFailure.AMBIGUOUS_ECOSYSTEM, assertThrows(SourceJarException::class.java) {
            MihonJarInspector().inspect(fixtures.sourceJar(name = "NovelD", packageName = "fixture.novel", metadata = both))
        }.failure)
        // Novel extensions on the 1.5 ABI never existed; it is not silently accepted.
        assertEquals(SourceJarFailure.UNSUPPORTED_LIBRARY, assertThrows(SourceJarException::class.java) {
            MihonJarInspector().inspect(fixtures.sourceJar(
                name = "NovelE", packageName = "fixture.novel",
                metadata = JarFixture.novelManifest(libraryMarker = false, versionName = "1.5.1"),
            ))
        }.failure)
    }

    @Test
    fun `anime manifests are classified as Aniyomi with the library read from the version name`() {
        val fixtures = JarFixture(directory)
        val metadata = MihonJarInspector().inspect(fixtures.sourceJar(
            name = "AnimeA", packageName = "fixture.anime", metadata = JarFixture.animeManifest(),
        ))
        assertEquals(SourceEcosystem.ANIYOMI, metadata.ecosystem)
        // The label's ecosystem prefix is provenance, not part of the name.
        assertEquals("Fixture 动画", metadata.displayName)
        assertEquals("14", metadata.extensionLib)
        assertEquals(listOf("fixture.anime.AnimeSource"), metadata.entryClasses)
        assertFalse(metadata.isNsfw)

        // The class and the factory entry points are both sources of entry classes.
        val both = MihonJarInspector().inspect(fixtures.sourceJar(
            name = "AnimeB", packageName = "fixture.anime", metadata = JarFixture.animeManifest(
                extra = """<meta-data android:name="tachiyomi.animeextension.factory" android:value=".Factory"/>"""),
        ))
        assertEquals(listOf("fixture.anime.AnimeSource", "fixture.anime.Factory"), both.entryClasses)

        // Library generations outside Aniyomi's supported range are refused, as is a manga + anime manifest.
        listOf("11.2", "17.0").forEach { version ->
            assertEquals(SourceJarFailure.UNSUPPORTED_LIBRARY, assertThrows(SourceJarException::class.java) {
                MihonJarInspector().inspect(fixtures.sourceJar(
                    name = "AnimeV${version.take(2)}", packageName = "fixture.anime",
                    metadata = JarFixture.animeManifest(versionName = version),
                ))
            }.failure)
        }
        assertEquals(SourceJarFailure.AMBIGUOUS_ECOSYSTEM, assertThrows(SourceJarException::class.java) {
            MihonJarInspector().inspect(fixtures.sourceJar(
                name = "AnimeC", packageName = "fixture.anime", metadata = JarFixture.animeManifest(
                    extra = """<meta-data android:name="tachiyomi.extension.class" android:value=".Single"/>"""),
            ))
        }.failure)
    }
    @Test
    fun `unqualified relative and absolute multiple entry names are normalized`() {
        val fixtures = JarFixture(directory)
        val path = fixtures.sourceJar(metadata = JarFixture.manifest(entry = "Single; .Other; other.extension.Factory"))
        assertEquals(
            listOf("fixture.extension.Single", "fixture.extension.Other", "other.extension.Factory"),
            MihonJarInspector().inspect(path).entryClasses,
        )
    }

    @Test
    fun `repo hash package and version mismatches are rejected individually`() {
        val fixtures = JarFixture(directory)
        val path = fixtures.sourceJar()
        val inspector = MihonJarInspector()
        val metadata = inspector.inspect(path)
        val identity = fixtures.identity(path)
        inspector.verify(metadata, identity.copy(sha256 = identity.sha256.uppercase()))
        listOf(
            identity.copy(sha256 = "0".repeat(64)) to SourceJarFailure.HASH_MISMATCH,
            identity.copy(sha256 = "invalid") to SourceJarFailure.HASH_MISMATCH,
            identity.copy(packageName = "other.package") to SourceJarFailure.PACKAGE_MISMATCH,
            identity.copy(versionCode = 17) to SourceJarFailure.VERSION_MISMATCH,
        ).forEach { (expected, code) ->
            assertEquals(code, assertThrows(SourceJarException::class.java) {
                inspector.verify(metadata, expected)
            }.failure)
        }
    }

    @Test
    fun `unsupported lib and binary or missing manifests are rejected`() {
        val fixtures = JarFixture(directory)
        val cases = listOf(
            null to SourceJarFailure.INVALID_MANIFEST,
            "binary-android-xml" to SourceJarFailure.INVALID_MANIFEST,
            JarFixture.manifest().replace("android:value=\"1.6\"", "android:value=\"1.7\"")
                to SourceJarFailure.UNSUPPORTED_LIBRARY,
        )
        cases.forEachIndexed { index, (manifest, code) ->
            val path = fixtures.jar(directory.resolve("bad-$index.jar"), manifest)
            assertEquals(code, assertThrows(SourceJarException::class.java) {
                MihonJarInspector().inspect(path)
            }.failure)
        }
    }

    @Test
    fun `external entities and duplicate entry metadata are rejected`() {
        val fixtures = JarFixture(directory)
        val manifest = JarFixture.manifest()
        val cases = listOf(
            manifest.replace("<manifest ", "<!DOCTYPE manifest SYSTEM \"file:///nonexistent\"><manifest "),
            manifest.replace("<application android:label=\"Fixture\">", """
                <application android:label="Fixture">
                    <meta-data android:name="tachiyomi.extension.class" android:value=".Other"/>
            """.trimIndent()),
        )
        cases.forEachIndexed { index, body ->
            val path = fixtures.jar(directory.resolve("xml-$index.jar"), body)
            assertEquals(SourceJarFailure.INVALID_MANIFEST, assertThrows(SourceJarException::class.java) {
                MihonJarInspector().inspect(path)
            }.failure)
        }
    }

    @Test
    fun `unsupported class versions are rejected before class loading`() {
        val fixtures = JarFixture(directory)
        val path = fixtures.sourceJar()
        assertEquals(SourceJarFailure.UNSUPPORTED_BYTECODE, assertThrows(SourceJarException::class.java) {
            MihonJarInspector(52).inspect(path)
        }.failure)
    }

    @Test
    fun `oversized manifest and duplicate declared entries are rejected`() {
        val fixtures = JarFixture(directory)
        val bodies = listOf(" ".repeat(1024 * 1024 + 1), JarFixture.manifest(entry = ".Single;.Single"))
        bodies.forEachIndexed { index, body ->
            val path = fixtures.jar(directory.resolve("oversized-$index.jar"), body)
            assertEquals(SourceJarFailure.INVALID_MANIFEST, assertThrows(SourceJarException::class.java) {
                MihonJarInspector().inspect(path)
            }.failure)
        }
    }

    @Test
    fun `unselected multi release entries do not raise bytecode ceiling`() {
        val fixtures = JarFixture(directory)
        val path = fixtures.jar(directory.resolve("multirelease.jar"), JarFixture.manifest(), extra = mapOf(
            "Single.class" to byteArrayOf(-54, -2, -70, -66, 0, 0, 0, 55),
            "META-INF/versions/99/Single.class" to byteArrayOf(-54, -2, -70, -66, 0, 0, 0, 99),
        ))
        assertEquals(55, MihonJarInspector(55).inspect(path).maximumClassVersion)
    }

    @Test
    fun `invalid class magic archive and missing bytecode fail explicitly`() {
        val fixtures = JarFixture(directory)
        val noClass = fixtures.jar(directory.resolve("no-class.jar"), JarFixture.manifest())
        val invalidClass = fixtures.jar(directory.resolve("bad-class.jar"), JarFixture.manifest(),
            extra = mapOf("Bad.class" to byteArrayOf(0, 0, 0, 0, 0, 0, 0, 55)))
        val notArchive = Files.writeString(directory.resolve("not-archive.jar"), "not a jar")
        listOf(noClass, invalidClass, notArchive).forEach { path ->
            assertEquals(SourceJarFailure.INVALID_ARCHIVE, assertThrows(SourceJarException::class.java) {
                MihonJarInspector().inspect(path)
            }.failure)
        }
    }
}
