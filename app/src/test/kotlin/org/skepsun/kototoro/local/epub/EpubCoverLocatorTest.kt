package org.skepsun.kototoro.local.epub

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class EpubCoverLocatorTest {

	private val container = """<?xml version="1.0"?><container version="1.0"
		xmlns="urn:oasis:names:tc:opendocument:xmlns:container"><rootfiles>
		<rootfile full-path="OPS/package.opf" media-type="application/oebps-package+xml"/></rootfiles></container>"""

	private fun opf(manifest: String, metadata: String = "") = """<?xml version="1.0" encoding="utf-8"?>
		<package xmlns="http://www.idpf.org/2007/opf" version="3.0"><metadata>$metadata</metadata>
		<manifest>$manifest</manifest></package>"""

	private fun locate(entries: Map<String, String?>) =
		EpubCoverLocator.locate(entries.keys) { name -> entries[name] }

	@Test
	fun `the EPUB 3 cover-image item wins over the first image`() {
		val entries = mapOf(
			"META-INF/container.xml" to container,
			"OPS/package.opf" to opf(
				"""<item id="a" href="art/a-first.png" media-type="image/png"/>
				<item id="j" href="art/jacket.jpg" media-type="image/jpeg" properties="cover-image"/>""",
			),
			"OPS/art/a-first.png" to null,
			"OPS/art/jacket.jpg" to null,
		)
		assertEquals("OPS/art/jacket.jpg", locate(entries))
	}

	@Test
	fun `the EPUB 2 cover meta resolves the manifest item relative to the package`() {
		val entries = mapOf(
			"META-INF/container.xml" to container,
			"OPS/package.opf" to opf(
				manifest = """<item id="img-1" href="../Images/Front%20Page.jpg" media-type="image/jpeg"/>""",
				metadata = """<meta name="cover" content="img-1"/>""",
			),
			"Images/Front Page.jpg" to null,
			"Images/a.png" to null,
		)
		assertEquals("Images/Front Page.jpg", locate(entries))
	}

	@Test
	fun `without a declared cover an image named cover is preferred, then the first image`() {
		val noCover = opf("""<item id="c1" href="c1.xhtml" media-type="application/xhtml+xml"/>""")
		assertEquals(
			"OPS/img/Cover.PNG",
			locate(mapOf("META-INF/container.xml" to container, "OPS/package.opf" to noCover, "OPS/img/a.png" to null, "OPS/img/Cover.PNG" to null)),
		)
		assertEquals(
			"OPS/img/a.png",
			locate(mapOf("META-INF/container.xml" to container, "OPS/package.opf" to noCover, "OPS/img/b.jpg" to null, "OPS/img/a.png" to null)),
		)
	}

	@Test
	fun `a declared cover missing from the archive falls back to the images present`() {
		val entries = mapOf(
			"META-INF/container.xml" to container,
			"OPS/package.opf" to opf("""<item id="j" href="gone.jpg" media-type="image/jpeg" properties="cover-image"/>"""),
			"OPS/only.webp" to null,
		)
		assertEquals("OPS/only.webp", locate(entries))
	}

	@Test
	fun `an archive without images has no cover`() {
		assertNull(locate(mapOf("META-INF/container.xml" to container, "OPS/package.opf" to opf(""))))
		assertNull(locate(emptyMap()))
	}
}
