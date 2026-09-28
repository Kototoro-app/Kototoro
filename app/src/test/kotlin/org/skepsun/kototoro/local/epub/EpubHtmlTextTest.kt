package org.skepsun.kototoro.local.epub

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class EpubHtmlTextTest {

	private val chapter = """<?xml version="1.0" encoding="utf-8"?>
		<html xmlns="http://www.w3.org/1999/xhtml"><head><title>My Book - Part 1</title>
		<meta charset="utf-8"/><link rel="stylesheet" href="s.css"/></head>
		<body><h2>Part 1</h2><p>Paragraph one.</p><p>Paragraph two.</p></body></html>"""

	@Test
	fun `the head title is not part of the chapter text`() {
		// The <title> of nearly every content document leaked into the text and ran into the body
		// heading: "My Book - Part 1Part 1".
		val text = epubHtmlToText(chapter, keepImages = true)
		assertFalse(text.contains("My Book"), text)
		assertEquals("Part 1\n\nParagraph one.\n\nParagraph two.", text)
	}

	@Test
	fun `block elements end a line instead of running into the next text`() {
		val html = "<body><h1>Title</h1><div>First block</div><div>Second block</div>" +
			"<ul><li>one</li><li>two</li></ul><blockquote>Quote</blockquote>After</body>"
		val lines = epubHtmlToText(html, keepImages = false).lines().filter(String::isNotBlank)
		assertEquals(listOf("Title", "First block", "Second block", "one", "two", "Quote", "After"), lines)
	}

	@Test
	fun `a document without a head keeps its body text`() {
		assertEquals("Only body.", epubHtmlToText("<p>Only body.</p>", keepImages = false))
	}

	@Test
	fun `images become placeholders only when kept`() {
		val html = """<body><p>Before</p><img src="../img/a.jpg" alt="A"/><p>After</p></body>"""
		assertTrue(epubHtmlToText(html, keepImages = true).contains("📷 [图片: ../img/a.jpg]"))
		assertFalse(epubHtmlToText(html, keepImages = false).contains("📷"))
	}
}
