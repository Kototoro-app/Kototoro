package org.skepsun.kototoro.settings.sources.unified

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class SourceAddressRoutingTest {

    @Test
    fun `a source configuration body decides its own kind`() {
        assertEquals(
            UnifiedSourceKind.TVBOX,
            resolveSourceKindForBody("""{"sites":[{"key":"a","api":"https://example.org/api.php/provide/vod"}]}"""),
        )
        assertEquals(
            UnifiedSourceKind.LEGADO,
            resolveSourceKindForBody("""{"bookSourceUrl":"https://x","ruleSearch":"//div"}"""),
        )
        assertEquals(
            UnifiedSourceKind.LEGADO,
            resolveSourceKindForBody("""[{"bookSourceUrl":"https://x"}]"""),
        )
    }

    @Test
    fun `a repository index is recognised by the packages it publishes`() {
        assertEquals(
            UnifiedSourceKind.MIHON,
            resolveSourceKindForBody(
                """{"packages":[{"name":"eu.kanade.tachiyomi.extension.all.mangadex","url":"a.apk"}]}""",
            ),
        )
        assertEquals(
            UnifiedSourceKind.ANIYOMI,
            resolveSourceKindForBody(
                """{"packages":[{"name":"eu.kanade.tachiyomi.animeextension.en.cr","url":"a.apk"}]}""",
            ),
        )
        assertEquals(
            UnifiedSourceKind.CLOUDSTREAM,
            resolveSourceKindForBody("""{"packages":[{"url":"https://example.org/plugin.cs3"}]}"""),
        )
        assertEquals(
            UnifiedSourceKind.JAR,
            resolveSourceKindForBody("""{"packages":[{"url":"https://example.org/parser.jar"}]}"""),
        )
    }

    @Test
    fun `a protobuf index is settled by its address alone`() {
        assertEquals(
            UnifiedSourceKind.MIHON,
            resolveSourceKindWithoutBody("https://example.org/repo/index.pb"),
        )
        assertEquals(
            UnifiedSourceKind.MIHON,
            resolveSourceKindWithoutBody("https://example.org/index.pb?token=abc"),
        )
        // A JSON index still needs its body, and an extension file is not a source at all.
        assertNull(resolveSourceKindWithoutBody("https://example.org/index.min.json"))
        assertNull(resolveSourceKindWithoutBody("https://example.org/plugin.jar"))
        assertNull(resolveSourceKindWithoutBody(""))
    }

    @Test
    fun `an unreadable body stays unresolved instead of guessing`() {
        assertNull(resolveSourceKindForBody("""{"hello":"world"}"""))
        assertNull(resolveSourceKindForBody("<html><body>404</body></html>"))
        assertNull(resolveSourceKindForBody(""))
    }
}
