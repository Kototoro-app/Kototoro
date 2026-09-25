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
