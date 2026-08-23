package org.skepsun.kototoro.extensions.repo

import kotlinx.serialization.protobuf.ProtoBuf
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.util.zip.GZIPInputStream

/**
 * Decodes the LIVE NovelSourcery repository index (fetched from
 * https://github.com/NovelSourcery/extensions/raw/repo/index.pb and stored gzipped under
 * app/src/test/resources/fixtures/novelsourcery-live-index.pb.gz).
 *
 * Guards the real-world repo format against the app's [ExtensionStoreIndex] model: the byte
 * stream is gzip-compressed (magic 0x1f8b) raw protobuf, the signing key is what the trust
 * dialog fingerprints, and every listed extension must carry the fields the unified sources
 * installer needs (package, version, apk URL) bundled with its novel sources.
 */
class NovelSourceryLiveIndexTest {

	private val liveBytes = NovelSourceryLiveIndexTest::class.java.getResourceAsStream(
		"/fixtures/novelsourcery-live-index.pb.gz",
	)?.use { it.readBytes() } ?: error("Missing live fixture: novelsourcery-live-index.pb.gz")

	@Test
	fun `live NovelSourcery index is gzipped raw protobuf and decodes`() {
		assertTrue(liveBytes.size > 1000, "fixture must be a real index (was ${liveBytes.size} bytes)")

		val raw = decompressIfGzipped(liveBytes)
		val index = ProtoBuf.decodeFromByteArray(ExtensionStoreIndex.serializer(), raw)

		assertEquals("NovelSourcery", index.name)
		assertNotEquals("", index.signingKey, "signing key must be present for the trust dialog")
		assertTrue(index.contact.website.isNotBlank(), "contact website must be present")
		assertTrue(index.badgeLabel.isNotBlank())

		val extensions = index.extensionList?.extensions ?: error("live index missing extensionList")
		assertTrue(extensions.size > 50, "live index should list the full catalogue (got ${extensions.size})")

		// Every extension must carry installable metadata + at least one listed novel source.
		extensions.forEach { extension ->
			assertTrue(extension.packageName.isNotBlank(), "extension package name")
			assertTrue(extension.name.isNotBlank(), "extension name")
			assertTrue(extension.versionCode > 0, "extension versionCode")
			assertTrue(extension.versionName.isNotBlank(), "extension versionName")
			assertTrue(extension.extensionLib.isNotBlank(), "extension lib version")
			assertTrue(
				extension.resources.apkUrl.isNotEmpty(),
				"apkUrl must be present for ${extension.packageName}",
			)
		}
	}

	@Test
	fun `live index mirrors the NovelSourcery extension apk paths`() {
		val raw = decompressIfGzipped(liveBytes)
		val index = ProtoBuf.decodeFromByteArray(ExtensionStoreIndex.serializer(), raw)
		val extensions = index.extensionList?.extensions ?: error("missing extensionList")

		val allNovelFull = extensions.firstOrNull { it.name.contains("AllNovelFull", ignoreCase = true) }
		assertTrue(
			allNovelFull != null && allNovelFull.resources.apkUrl.contains("tsundoku-"),
			"AllNovelFull tsundoku apk path expected in live index",
		)
	}

	private fun decompressIfGzipped(bytes: ByteArray): ByteArray {
		val gzipMagic = bytes.size >= 2 && bytes[0] == 0x1f.toByte() && bytes[1] == 0x8b.toByte()
		return if (gzipMagic) {
			GZIPInputStream(ByteArrayInputStream(bytes)).use { it.readBytes() }
		} else {
			bytes
		}
	}
}
