package org.skepsun.kototoro.local.epub

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import java.io.File

/**
 * EbookTextParser 单元测试：FB2 / TXT 文本解析与章节拆分。
 *
 * 仅验证成功路径（成功时不依赖 Android Log，可在 JVM 运行）。
 */
class EbookTextParserTest : StringSpec({

    val tempDir = File(System.getProperty("java.io.tmpdir"), "ebook-text-parser-test-${System.nanoTime()}")

    fun writeFile(name: String, content: String): File {
        tempDir.mkdirs()
        return File(tempDir, name).apply { writeText(content) }
    }

    afterSpec {
        tempDir.deleteRecursively()
    }

    "parseFb2 提取书名、作者与章节标题、段落" {
        val fb2 = writeFile(
            "test.fb2",
            """<?xml version="1.0" encoding="UTF-8"?>
<FictionBook xmlns="http://www.gribuser.ru/xml/fictionbook/2.0">
  <description>
    <title-info>
      <book-title>沉思录</book-title>
      <author><first-name>马可</first-name><last-name>奥勒留</last-name></author>
    </title-info>
  </description>
  <body>
    <section>
      <title><p>第一卷</p></title>
      <p>卷一第一段。</p>
      <p>卷一第二段。</p>
    </section>
    <section>
      <title><p>第二卷</p></title>
      <p>卷二正文。</p>
    </section>
  </body>
</FictionBook>""",
        )
        val content = runBlocking { EbookTextParser().parseFb2(fb2) }
        content.shouldNotBeNull()
        content.title shouldBe "沉思录"
        content.author shouldBe "马可 奥勒留"
        content.chapters.size shouldBe 2
        content.chapters[0].title shouldBe "第一卷"
        content.chapters[0].content shouldBe "卷一第一段。\n\n卷一第二段。"
        content.chapters[1].title shouldBe "第二卷"
        content.chapters[1].content shouldBe "卷二正文。"
    }

    "parseTxt 无章节标题时整本作为一章" {
        val raw = "这是正文第一段。\n这是正文第二段。"
        val txt = writeFile("plain.txt", raw)
        val content = runBlocking { EbookTextParser().parseTxt(txt) }
        content.shouldNotBeNull()
        content.chapters.size shouldBe 1
        content.chapters[0].content shouldBe raw.trim()
    }

    "parseTxt 按 第X章 拆分中文章节" {
        val txt = writeFile(
            "cn.txt",
            """第一章 开端
这是第一章的正文。

第二章 发展
这里是第二章的正文内容。

第三章 结局
第三章的最后一章。""",
        )
        val content = runBlocking { EbookTextParser().parseTxt(txt) }
        content.shouldNotBeNull()
        content.chapters.size shouldBe 3
        content.chapters[0].title shouldBe "第一章 开端"
        content.chapters[0].content shouldBe "这是第一章的正文。"
        content.chapters[1].title shouldBe "第二章 发展"
        content.chapters[2].title shouldBe "第三章 结局"
    }

    "parseTxt 按 Chapter N 拆分英文章节" {
        val txt = writeFile(
            "en.txt",
            """Chapter 1 The Beginning
First paragraph.

Chapter 2 The End
Second chapter text.""",
        )
        val content = runBlocking { EbookTextParser().parseTxt(txt) }
        content.shouldNotBeNull()
        content.chapters.size shouldBe 2
        content.chapters[0].title shouldBe "Chapter 1 The Beginning"
        content.chapters[0].content shouldBe "First paragraph."
        content.chapters[1].title shouldBe "Chapter 2 The End"
        content.chapters[1].content shouldBe "Second chapter text."
    }

    "parseTxt 对空白文件返回空章节" {
        val txt = writeFile("empty.txt", "   \n  ")
        val content = runBlocking { EbookTextParser().parseTxt(txt) }
        content.shouldNotBeNull()
        content.chapters shouldBe emptyList()
    }
})
