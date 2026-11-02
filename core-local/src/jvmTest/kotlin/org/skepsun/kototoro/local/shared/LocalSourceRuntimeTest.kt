package org.skepsun.kototoro.local.shared

import kotlinx.coroutines.test.runTest
import okio.Path
import okio.Path.Companion.toOkioPath
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.skepsun.kototoro.core.source.SourceDetailsFetchMode
import org.skepsun.kototoro.core.source.SourceFilter
import java.io.File
import java.nio.charset.Charset
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class LocalSourceRuntimeTest {
    @TempDir
    lateinit var dir: File

    private val png = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 1, 2, 3)

    private fun runtime() = LocalSourceRuntime(
        File(dir, "store").toOkioPath(), File(dir, "images").toOkioPath(),
        decodeLegacyText = { String(it, Charset.forName("GB18030")) },
    )

    private fun zip(name: String, entries: Map<String, ByteArray>): Path {
        val file = File(dir, name)
        ZipOutputStream(file.outputStream()).use { out ->
            entries.forEach { (entry, bytes) -> out.putNextEntry(ZipEntry(entry)); out.write(bytes); out.closeEntry() }
        }
        return file.toOkioPath()
    }

    @Test
    fun comicInfoDescribesArchivesWithoutAnIndex() = runTest {
        val comicInfo = """<?xml version="1.0"?><ComicInfo><Series>Witch Hat Atelier</Series>
            |<Title>The Return</Title><Number>12</Number><Summary>Coco learns magic.</Summary>
            |<Writer>Kamome Shirahama</Writer><Genre>Fantasy, Adventure</Genre></ComicInfo>""".trimMargin()
        val file = zip("ch12.cbz", mapOf(
            "ComicInfo.xml" to comicInfo.encodeToByteArray(),
            "001.png" to png,
            "002.png" to png,
        ))
        val runtime = runtime()
        val details = runtime.getDetails(runtime.import(file, "ch12.cbz"), SourceDetailsFetchMode.ALLOW_CACHE)
        assertEquals("Witch Hat Atelier", details.title)
        assertEquals("Coco learns magic.", details.description)
        assertEquals(setOf("Kamome Shirahama"), details.authors)
        assertEquals(setOf("Fantasy", "Adventure"), details.tags.map { it.title }.toSet())
        assertEquals(listOf("The Return"), details.chapters!!.map { it.title })
        assertEquals(12f, details.chapters!!.single().number)
        assertEquals(2, runtime.getPages(details.chapters!!.single(), null).size)
    }

    @Test
    fun epubSectionsOfOneDocumentBecomeChaptersForNewImportsOnly() = runTest {
        val opf = """<?xml version="1.0"?><package xmlns="http://www.idpf.org/2007/opf" version="3.0">
            |<metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:title>分节</dc:title></metadata>
            |<manifest><item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>
            |<item id="all" href="text/all.xhtml" media-type="application/xhtml+xml"/>
            |<item id="end" href="text/end.xhtml" media-type="application/xhtml+xml"/></manifest>
            |<spine><itemref idref="all"/><itemref idref="end"/></spine></package>""".trimMargin()
        val nav = """<html><body><nav epub:type="toc"><ol>
            |<li><a href="text/all.xhtml">卷一</a><ol><li><a href="text/all.xhtml#c1">第一章</a></li>
            |<li><a href="text/all.xhtml#c2">第二章</a></li><li><a href="text/all.xhtml#gone">不存在</a></li></ol></li>
            |<li><a href="text/end.xhtml">后记</a></li></ol></nav>
            |<nav epub:type="landmarks"><ol><li><a href="text/all.xhtml#c2">正文</a></li></ol></nav></body></html>""".trimMargin()
        val all = """<html><body><h1>卷一</h1><div class="part"><h2 id="c1">第一章</h2><p>甲</p>
            |<h2 id="c2">第二章</h2><p>乙</p></div></body></html>""".trimMargin()
        val file = zip("sections.epub", mapOf(
            "mimetype" to "application/epub+zip".encodeToByteArray(),
            "META-INF/container.xml" to """<container><rootfiles><rootfile full-path="OEBPS/content.opf"/></rootfiles></container>""".encodeToByteArray(),
            "OEBPS/content.opf" to opf.encodeToByteArray(),
            "OEBPS/nav.xhtml" to nav.encodeToByteArray(),
            "OEBPS/text/all.xhtml" to all.encodeToByteArray(),
            "OEBPS/text/end.xhtml" to "<html><body><p>完</p></body></html>".encodeToByteArray(),
        ))
        val runtime = runtime()
        val imported = runtime.import(file, "sections.epub")
        val details = runtime.getDetails(imported, SourceDetailsFetchMode.ALLOW_CACHE)
        assertEquals(listOf("卷一", "第一章", "第二章", "后记"), details.chapters!!.map { it.title })
        val texts = details.chapters!!.map { runtime.getChapterContent(it, null).html }
        assertTrue(texts[0].contains("<h1>卷一</h1>") && !texts[0].contains("甲"), texts[0])
        assertTrue(texts[1].contains("甲") && !texts[1].contains("乙"), texts[1])
        assertTrue(texts[2].contains("乙") && !texts[2].contains("甲"), texts[2])
        assertTrue(texts[3].contains("完"), texts[3])

        // A work imported before sections were split keeps one chapter per document.
        val meta = File(dir, "store/items/${imported.id}/meta.json")
        val legacy = meta.readText().replace(Regex(""",?"chapterScheme":\d+"""), "")
        meta.writeText(legacy)
        val old = runtime().getDetails(imported, SourceDetailsFetchMode.ALLOW_CACHE)
        assertEquals(listOf("卷一", "后记"), old.chapters!!.map { it.title })
        assertTrue(runtime().getChapterContent(old.chapters!!.first(), null).html.contains("乙"))
    }

    @Test
    fun epubChaptersCarryTheirStyleSheets() = runTest {
        val opf = """<?xml version="1.0"?><package xmlns="http://www.idpf.org/2007/opf" version="3.0">
            |<metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:title>样式</dc:title></metadata>
            |<manifest><item id="c1" href="text/c1.xhtml" media-type="application/xhtml+xml"/>
            |<item id="css" href="styles/book.css" media-type="text/css"/></manifest>
            |<spine><itemref idref="c1"/></spine></package>""".trimMargin()
        val chapter = """<html><head><title>T</title><link rel="stylesheet" type="text/css" href="../styles/book.css"/>
            |<style>.note { font-size: small }</style><link rel="icon" href="x.png"/></head>
            |<body><h1 class="title">第一章</h1><p class="noindent">正文</p></body></html>""".trimMargin()
        val file = zip("styled.epub", mapOf(
            "mimetype" to "application/epub+zip".encodeToByteArray(),
            "META-INF/container.xml" to """<container><rootfiles><rootfile full-path="OEBPS/content.opf"/></rootfiles></container>""".encodeToByteArray(),
            "OEBPS/content.opf" to opf.encodeToByteArray(),
            "OEBPS/text/c1.xhtml" to chapter.encodeToByteArray(),
            "OEBPS/styles/book.css" to "h1.title { text-align: center }\n.noindent { text-indent: 0 }".encodeToByteArray(),
        ))
        val runtime = runtime()
        val details = runtime.getDetails(runtime.import(file, "styled.epub"), SourceDetailsFetchMode.ALLOW_CACHE)
        val html = runtime.getChapterContent(details.chapters!!.single(), null).html
        assertTrue(html.startsWith("<style>h1.title { text-align: center }"), html)
        assertTrue(html.indexOf(".note { font-size: small }") in 1 until html.indexOf("</style>"), html)
        assertTrue(html.contains("<p class=\"noindent\">正文</p>"), html)
        assertFalse(html.contains("<title>"), html)
    }

    /** Archives Kototoro writes on Android: index.json + `%08d_%04d%04d` entries for every chapter. */
    @Test
    fun kototoroArchiveKeepsItsChaptersAndMetadata() = runTest {
        val index = """{"id":1,"title":"下载的漫画","authors":["作者"],"description":"简介","source":"MANGADEX",
            |"tags":[{"key":"action","title":"Action"}],"cover_entry":"00000000_00020001.png",
            |"chapters":{"11":{"name":"第一话","number":1,"order":1,"url":"/c/1"},
            |"12":{"name":"第二话","number":2,"order":2,"url":"/c/2","entries":"00000000_0002\\d{4}"}}}""".trimMargin()
        val file = zip("downloaded.cbz", mapOf(
            "index.json" to index.encodeToByteArray(),
            "00000000_00010002.png" to png, "00000000_00010001.png" to png,
            "00000000_00020001.png" to png,
        ))
        val runtime = runtime()
        val imported = runtime.import(file, "downloaded.cbz")
        assertEquals("LOCAL", imported.source.name)
        val details = runtime.getDetails(imported, SourceDetailsFetchMode.ALLOW_CACHE)
        assertEquals("下载的漫画", details.title)
        assertEquals(setOf("作者"), details.authors)
        assertEquals(listOf("第一话", "第二话"), details.chapters!!.map { it.title })
        val pages = runtime.getPages(details.chapters!!.first(), null)
        assertEquals(listOf("00000000_00010001.png", "00000000_00010002.png"), pages.map { it.url.substringAfterLast('/') })
        val image = runtime.fetchImage(pages.first())
        assertTrue(File(dir, "images/${image.relativePath}").readBytes().contentEquals(png))
        assertEquals("image/png", image.contentType)
        assertEquals(image.sha256, runtime.fetchCover(details).sha256)
    }

    @Test
    fun plainArchiveFoldersBecomeChaptersInNaturalOrder() = runTest {
        val file = zip("folders.zip", mapOf(
            "Vol/ch10/1.jpg" to png, "Vol/ch2/10.jpg" to png, "Vol/ch2/2.jpg" to png,
            "__MACOSX/Vol/ch2/._2.jpg" to png, "notes.txt" to "x".encodeToByteArray(),
        ))
        val runtime = runtime()
        val details = runtime.getDetails(runtime.import(file, "folders.zip"), SourceDetailsFetchMode.ALLOW_CACHE)
        assertEquals("folders", details.title)
        assertEquals(listOf("ch2", "ch10"), details.chapters!!.map { it.title })
        assertEquals(listOf("2.jpg", "10.jpg"), runtime.getPages(details.chapters!!.first(), null).map { it.url.substringAfterLast('/') })
    }

    @Test
    fun epubChaptersUseTheTableOfContentsAndExtractPictures() = runTest {
        val opf = """<?xml version="1.0"?><package xmlns="http://www.idpf.org/2007/opf" version="3.0">
            |<metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:title>测试小说</dc:title><dc:creator>某人</dc:creator>
            |<meta name="cover" content="cover"/></metadata>
            |<manifest><item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>
            |<item id="c1" href="text/c1.xhtml" media-type="application/xhtml+xml"/>
            |<item id="c2" href="text/c2.xhtml" media-type="application/xhtml+xml"/>
            |<item id="cover" href="images/cover.png" media-type="image/png"/></manifest>
            |<spine><itemref idref="c1"/><itemref idref="c2"/></spine></package>""".trimMargin()
        val nav = """<html><body><nav epub:type="toc"><ol><li><a href="text/c1.xhtml">第一章 开始</a></li>
            |<li><a href="text/c2.xhtml#p">第二章 &amp; 结束</a></li></ol></nav></body></html>""".trimMargin()
        val file = zip("book.epub", mapOf(
            "mimetype" to "application/epub+zip".encodeToByteArray(),
            "META-INF/container.xml" to """<container><rootfiles><rootfile full-path="OEBPS/content.opf"/></rootfiles></container>""".encodeToByteArray(),
            "OEBPS/content.opf" to opf.encodeToByteArray(),
            "OEBPS/nav.xhtml" to nav.encodeToByteArray(),
            "OEBPS/text/c1.xhtml" to "<html><body><p>你好</p><img src=\"../images/cover.png\"/></body></html>".encodeToByteArray(),
            "OEBPS/text/c2.xhtml" to "<html><body><p>再见</p></body></html>".encodeToByteArray(),
            "OEBPS/images/cover.png" to png,
        ))
        val runtime = runtime()
        val imported = runtime.import(file, "book.epub")
        assertEquals("LOCAL_NOVEL", imported.source.name)
        val details = runtime.getDetails(imported, SourceDetailsFetchMode.ALLOW_CACHE)
        assertEquals("测试小说", details.title)
        assertEquals(listOf("第一章 开始", "第二章 & 结束"), details.chapters!!.map { it.title })
        val text = runtime.getChapterContent(details.chapters!!.first(), null)
        assertTrue(text.html.contains("<p>你好</p>"), text.html)
        val image = text.images.single().url
        assertTrue(image.startsWith("file://"), image)
        assertTrue(text.html.contains(image))
        assertNotNull(runtime.fetchCover(details))
    }

    @Test
    fun legacyTextSplitsAtHeadings() = runTest {
        val body = "简介文字，介绍这本书的内容，足够长足够长。\n第一章 起点\n　　正文一。\n第二章 终点\n正文二。\n"
        val file = File(dir, "旧小说.txt").apply { writeBytes(body.toByteArray(Charset.forName("GB18030"))) }.toOkioPath()
        val runtime = runtime()
        val details = runtime.getDetails(runtime.import(file, "旧小说.txt"), SourceDetailsFetchMode.ALLOW_CACHE)
        assertEquals("旧小说", details.title)
        assertNull(details.coverUrl)
        assertEquals(listOf("前言", "第一章 起点", "第二章 终点"), details.chapters!!.map { it.title })
        assertEquals("<p>正文一。</p>", runtime.getChapterContent(details.chapters!![1], null).html)
    }

    @Test
    fun textWithoutHeadingsIsCutIntoParts() {
        val text = (1..3000).joinToString("\n") { "第$it 行的内容" }
        val parts = LocalBookReader.splitText(text)
        assertTrue(parts.size > 1)
        assertEquals(text.length, parts.last().textEnd)
        parts.zipWithNext().forEach { (a, b) -> assertEquals(a.textEnd, b.textStart) }
    }

    @Test
    fun listingSearchReimportAndRemoval() = runTest {
        val runtime = runtime()
        val first = runtime.import(zip("a.cbz", mapOf("1.png" to png)), "a.cbz")
        runtime.import(zip("b.cbz", mapOf("1.png" to png)), "b.cbz")
        assertEquals(setOf("a", "b"), runtime.getList("LOCAL", 0, null, null).map { it.title }.toSet())
        assertEquals(listOf("a"), runtime.getList("LOCAL", 0, null, SourceFilter(query = "A")).map { it.title })
        assertTrue(runtime.getList("LOCAL_NOVEL", 0, null, null).isEmpty())
        // The same file again replaces the earlier copy.
        runtime.import(zip("a.cbz", mapOf("1.png" to png)), "a.cbz")
        assertEquals(2, runtime.getList("LOCAL", 0, null, null).size)
        val listed = runtime.getList("LOCAL", 0, LocalSourceRuntime.ORDER_TITLE, null)
        assertEquals(listOf("a", "b"), listed.map { it.title })
        runtime.remove(listed.first().id)
        assertEquals(listOf("b"), runtime.getList("LOCAL", 0, null, null).map { it.title })
        assertFalse(runtime.getList("LOCAL", 0, null, null).any { it.id == first.id })
    }

    @Test
    fun unsupportedOrBrokenFilesLeaveNothingBehind() = runTest {
        val runtime = runtime()
        assertThrows(IllegalArgumentException::class.java) {
            kotlinx.coroutines.runBlocking { runtime.import(zip("x.pdf", mapOf("1.png" to png)), "x.pdf") }
        }
        assertThrows(IllegalArgumentException::class.java) {
            kotlinx.coroutines.runBlocking { runtime.import(zip("empty.cbz", mapOf("readme.txt" to png)), "empty.cbz") }
        }
        assertTrue(runtime.getList("LOCAL", 0, null, null).isEmpty())
        assertTrue(File(dir, "store/items").listFiles().orEmpty().isEmpty())
    }

    @Test
    fun videoIsCopiedAndReopensWithStableChaptersAndAnEncodedFileUrl() = runTest {
        val file = File(dir, "动画 #1.MP4").apply { writeBytes(byteArrayOf(1, 2, 3, 4)) }
        val runtime = runtime()
        val content = runtime.import(file.toOkioPath(), file.name)
        assertEquals("LOCAL_VIDEO", content.source.name)
        assertNull(content.coverUrl)
        val chapter = content.chapters!!.single()
        val pages = runtime.getPages(chapter, null)
        assertEquals(1, pages.size)
        assertEquals(content.source, pages.single().source)
        val stored = File(java.net.URI(pages.single().url))
        assertTrue(stored.readBytes().contentEquals(file.readBytes()))
        assertTrue(stored.canonicalPath.startsWith(File(dir, "store").canonicalPath))
        file.delete() // Playback is independent of the document provider's original file.
        val reopened = runtime().getDetails(content, SourceDetailsFetchMode.ALLOW_CACHE)
        assertEquals(chapter.id, reopened.chapters!!.single().id)
        assertEquals(pages, runtime().getPages(reopened.chapters!!.single(), null))
        assertTrue(runtime.describe("LOCAL_VIDEO").isCoverFetchingSupported.not())
        assertEquals(listOf(content.id), runtime.getList("LOCAL_VIDEO", 0, null, null).map { it.id })
        runtime.remove(content.id)
        assertFalse(stored.exists())
    }

    @Test
    fun reimportKeepsIdentityAndEqualSizedDifferentFilesDoNotOverwriteEachOther() = runTest {
        val file = File(dir, "video.mp4").apply { writeBytes(byteArrayOf(1, 2, 3, 4)) }
        val runtime = runtime()
        val first = runtime.import(file.toOkioPath(), file.name)
        val again = runtime.import(file.toOkioPath(), file.name)
        assertEquals(first.id, again.id)
        assertEquals(first.chapters, again.chapters)
        file.writeBytes(byteArrayOf(4, 3, 2, 1))
        val changed = runtime.import(file.toOkioPath(), file.name)
        assertTrue(first.id != changed.id)
        assertEquals(2, runtime.getList("LOCAL_VIDEO", 0, null, null).size)
        val old = File(java.net.URI(runtime.getPages(first.chapters!!.single(), null).single().url))
        assertTrue(old.readBytes().contentEquals(byteArrayOf(1, 2, 3, 4)))
    }

    @Test
    fun failedReimportPreservesThePreviouslyReadableWork() = runTest {
        val file = File(dir, "novel.txt").apply { writeText("第一章\n原来的正文") }
        val runtime = runtime()
        val content = runtime.import(file.toOkioPath(), file.name)
        file.writeText("")
        assertThrows(IllegalArgumentException::class.java) {
            kotlinx.coroutines.runBlocking { runtime.import(file.toOkioPath(), file.name) }
        }
        val reopened = runtime().getDetails(content, SourceDetailsFetchMode.ALLOW_CACHE)
        assertTrue(runtime().getChapterContent(reopened.chapters!!.single(), null).html.contains("原来的正文"))
    }

    @Test
    fun emptyVideoAndStandalonePlaylistsLeaveNoImportedFiles() = runTest {
        val runtime = runtime()
        for ((name, text) in listOf("empty.mp4" to "", "playlist.M3U8" to "#EXTM3U\nsegment.ts")) {
            val file = File(dir, name).apply { writeText(text) }
            assertThrows(IllegalArgumentException::class.java) {
                kotlinx.coroutines.runBlocking { runtime.import(file.toOkioPath(), name) }
            }
        }
        assertTrue(runtime.getList("LOCAL_VIDEO", 0, null, null).isEmpty())
        assertTrue(File(dir, "store/items").listFiles().orEmpty().isEmpty())
    }

    @Test
    fun aVideoFolderBecomesOneWorkWithAnEpisodePerFileAndKeepsItsIdOnReimport() = runTest {
        val folder = File(dir, "show").apply { mkdirs() }
        val files = listOf("Ep10.mp4", "Ep2.mp4", "cover.jpg", "Ep1.mkv").map { name ->
            File(folder, name).apply { writeBytes(name.encodeToByteArray()) }
        }.map { it.toOkioPath() to it.name }
        val runtime = runtime()
        val first = runtime.importVideoFolder(files, "Show")
        val details = runtime.getDetails(first, SourceDetailsFetchMode.ALLOW_CACHE)
        // Natural order, pictures skipped.
        assertEquals(listOf("Ep1", "Ep2", "Ep10"), details.chapters!!.map { it.title })
        val page = runtime.getPages(details.chapters!![2], null).single()
        assertTrue(page.url.startsWith("file://") && page.url.endsWith("/episodes/2.mp4"), page.url)
        assertEquals(first.id, runtime.importVideoFolder(files.reversed(), "Show").id)
        assertThrows(IllegalArgumentException::class.java) {
            kotlinx.coroutines.runBlocking { runtime.importVideoFolder(listOf(files[2]), "Pictures") }
        }
    }
}
