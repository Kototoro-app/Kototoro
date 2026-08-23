package org.skepsun.kototoro.extensions.repo

import kotlinx.serialization.protobuf.ProtoBuf
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.util.zip.GZIPInputStream

/**
 * Decodes the hand-crafted NovelSourcery index protobuf fixtures produced by
 * scripts/make_novelsourcery_fixture.py under app/src/test/resources/fixtures/.
 *
 * Covers: plain and gzipped payloads, absolute vs relative resource URLs, and
 * forward compatibility — unknown top-level fields (7000 / 8000 / 2000 / 6000)
 * must be skipped without failing decode.
 */
class NovelSourceryIndexFixtureTest {

	@Test
	fun `decodes plain protobuf fixture and skips unknown fields`() {
		assertIndex(decodeFixture("/fixtures/novelsourcery-index.protobuf"))
	}

	@Test
	fun `decodes gzipped protobuf fixture and skips unknown fields`() {
		assertIndex(decodeFixture("/fixtures/novelsourcery-index.protobuf.gz"))
	}

	@Test
	fun `unknown top level fields do not leak into modeled optional fields`() {
		val index = decodeFixture("/fixtures/novelsourcery-index.protobuf")

		assertNull(index.extensionListUrl)
		val source = index.extensionList?.extensions?.firstOrNull()?.sources?.firstOrNull()
		assertNotNull(source)
		assertNull(source!!.message)
	}

	@Test
	fun `ExtensionStoreIndex decodes NovelSourcery fixture into non empty store data`() {
		val index: ExtensionStoreIndex = decodeFixture("/fixtures/novelsourcery-index.protobuf")

		assertTrue(index.name.isNotBlank(), "store name must be present")
		assertTrue(index.signingKey.isNotBlank(), "signing key must be present")
		assertNotNull(index.extensionList)
		assertTrue(index.extensionList!!.extensions.isNotEmpty(), "extension list must have content")
	}

	private fun decodeFixture(resource: String): ExtensionStoreIndex {
		val bytes = NovelSourceryIndexFixtureTest::class.java.getResourceAsStream(resource)
			?.use { it.readBytes() }
			?: error("Missing fixture resource: $resource")
		val raw = if (resource.endsWith(".gz")) {
			GZIPInputStream(ByteArrayInputStream(bytes)).use { it.readBytes() }
		} else {
			bytes
		}
		return ProtoBuf.decodeFromByteArray(ExtensionStoreIndex.serializer(), raw)
	}

	private fun assertIndex(index: ExtensionStoreIndex) {
		assertEquals("NovelSourcery Test", index.name)
		assertEquals("novel", index.badgeLabel)
		assertEquals("test-signing-key-hex", index.signingKey)
		assertEquals("https://example.org/repo", index.contact.website)
		assertEquals("https://example.org/discord", index.contact.discord)

		val extensions = index.extensionList?.extensions
			?: error("Missing extensionList in fixture")
		assertEquals(1, extensions.size)
		val extension = extensions[0]

		assertEquals("Example Novel", extension.name)
		assertEquals(
			"eu.kanade.tachiyomi.extension.en.novel-example",
			extension.packageName,
		)
		assertEquals("1.6", extension.extensionLib)
		assertEquals(12L, extension.versionCode)
		assertEquals("1.6.12", extension.versionName)
		assertEquals(ExtensionStoreIndex.ContentWarning.SAFE, extension.contentWarning)

		assertEquals("https://repo.example.org/novel-example.apk", extension.resources.apkUrl)
		assertTrue(extension.resources.apkUrl.startsWith("https://"), "apkUrl must be absolute")
		assertEquals("/icons/novel-example.png", extension.resources.iconUrl)
		assertFalse(extension.resources.iconUrl.startsWith("http"), "iconUrl must be relative")
		assertEquals("https://repo.example.org/novel-example.jar", extension.resources.jarUrl)

		val sources = extension.sources
		assertEquals(1, sources.size)
		val source = sources[0]
		assertEquals(9001L, source.id)
		assertEquals("Example Novel", source.name)
		assertEquals("en", source.language)
		assertEquals("https://example.org", source.homeUrl)
		assertEquals(2, source.mirrorUrls.size)
		assertEquals(
			listOf("https://mirror1.example.org", "https://mirror2.example.org"),
			source.mirrorUrls,
		)
	}
}
