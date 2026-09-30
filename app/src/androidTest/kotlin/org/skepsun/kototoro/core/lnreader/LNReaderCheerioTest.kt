package org.skepsun.kototoro.core.lnreader

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LNReaderCheerioTest {

    @Test
    fun contentsPreservesTextCommentsAndOuterHtml() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val engine = LNReaderEngine(context, LNReaderFetchBridge(OkHttpClient(), "contents"))
        val qjs = engine.createPluginContext("", "contents")
        try {
            val result = qjs.evaluate<String>(
                """
                (function() {
                    const ${'$'} = require('cheerio').load('<nav>outside</nav><div id="chapter">Hello <b>world</b><br>Next<!--note--></div>');
                    return JSON.stringify(${'$'}('#chapter').contents().toArray().map(function(node) {
                        return { type: node.type, data: node.data, html: ${'$'}.html(node) };
                    }));
                })()
                """.trimIndent(),
            )
            val nodes = org.json.JSONArray(result)
            assertEquals(5, nodes.length())
            assertEquals("text", nodes.getJSONObject(0).getString("type"))
            assertEquals("Hello ", nodes.getJSONObject(0).getString("data"))
            assertEquals("<b>world</b>", nodes.getJSONObject(1).getString("html"))
            assertEquals("<br>", nodes.getJSONObject(2).getString("html"))
            assertEquals("comment", nodes.getJSONObject(4).getString("type"))
        } finally {
            qjs.close()
        }
    }

    @Test
    fun chapterNodeSerializationDoesNotRepeatTheWholeDocument() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val engine = LNReaderEngine(context, LNReaderFetchBridge(OkHttpClient(), "large-chapter"))
        val qjs = engine.createPluginContext("", "large-chapter")
        try {
            // Read From Net serializes every node returned by contents(). Keep a realistic document
            // size and enough line breaks to expose repeated whole-document serialization/OOM.
            val length = qjs.evaluate<Long>(
                """
                (function() {
                    const ${'$'} = require('cheerio').load('<nav>' + 'x'.repeat(100000) + '</nav><div id="chapter">' + 'line<br>'.repeat(600) + '</div>');
                    const nodes = ${'$'}('#chapter').contents().toArray();
                    return nodes.map(function(node) { return ${'$'}.html(node); }).join('').length;
                })()
                """.trimIndent(),
            )
            assertEquals(4800L, length)
        } finally {
            qjs.close()
        }
    }

    @Test
    fun testCheerioMutationsAndFormatting() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val fetchBridge = LNReaderFetchBridge(OkHttpClient(), "test")
        val engine = LNReaderEngine(context, fetchBridge)
        val qjs = engine.createPluginContext("", "test")

        try {
            // Evaluate a script using the Cheerio bridge that mimics NovelBuddy's parseNovel summary extraction
            val script = """
                (function() {
                    const cheerio = require('cheerio');
                    const u = { summary: 'Line 1<br>Line 2<p>Paragraph 1</p><p>Paragraph 2</p>' };
                    const s = {};
                    let p, h;
                    (p = u.summary || '') && (
                        (h = cheerio.load('<div>' + p + '</div>'))('br').replaceWith('\n'),
                        h('p').before('\n').after('\n\n'),
                        s.summary = h('div').text().split('\n').map(function(e) { return e.trim(); }).filter(function(e) { return e.length > 0; }).join('\n\n').trim()
                    );
                    
                    // Test bracket indexing and attribs
                    const pElements = h('p');
                    const firstP = pElements[0];
                    
                    // Test proxy fallback on missing method
                    const chained = h('p').nonExistentCheerioMethod().first();

                    // Test traversal: has, add, siblings
                    const hasP = h('div').has('p');

                    return JSON.stringify({
                        summary: s.summary,
                        firstPTag: firstP ? firstP.tagName : null,
                        chainedExists: chained !== null,
                        hasPLength: hasP.length
                    });
                })()
            """.trimIndent()

            val resultStr = qjs.evaluate<String>(script)
            assertNotNull(resultStr)
            println("Cheerio test result: $resultStr")

            assertTrue("Expected summary to contain Line 1, was: $resultStr", resultStr.contains("Line 1"))
            assertTrue("Expected summary to contain Line 2, was: $resultStr", resultStr.contains("Line 2"))
            assertTrue("Expected summary to contain Paragraph 1, was: $resultStr", resultStr.contains("Paragraph 1"))
            assertTrue("Expected summary to contain Paragraph 2, was: $resultStr", resultStr.contains("Paragraph 2"))
            assertTrue("Expected hasPLength == 1, was: $resultStr", resultStr.contains("\"hasPLength\":1"))
            assertTrue("Expected chainedExists == true, was: $resultStr", resultStr.contains("\"chainedExists\":true"))
        } finally {
            qjs.close()
        }
    }
}
