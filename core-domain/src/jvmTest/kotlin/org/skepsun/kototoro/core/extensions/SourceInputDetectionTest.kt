package org.skepsun.kototoro.core.extensions

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class SourceInputDetectionTest {

    @Test
    fun `addresses are recognised without the network`() {
        assertEquals(SourceInputIntent.SOURCE_CONFIG, detectSourceInput("tvbox://config/abc"))
        assertEquals(SourceInputIntent.SOURCE_CONFIG, detectSourceInput("legado://import/bookSource"))
        assertEquals(SourceInputIntent.SOURCE_CONFIG, detectSourceInput("  https://example.org/sites.json  "))
        assertEquals(SourceInputIntent.REPOSITORY_INDEX, detectSourceInput("https://example.org/index.min.json"))
        assertEquals(SourceInputIntent.REPOSITORY_INDEX, detectSourceInput("https://example.org/repo.pb"))
        assertEquals(SourceInputIntent.EXTENSION_FILE, detectSourceInput("/sdcard/Download/aniyomi-all.apk"))
        assertEquals(SourceInputIntent.EXTENSION_FILE, detectSourceInput("https://example.org/ext.jar"))
        assertEquals(SourceInputIntent.EXTENSION_FILE, detectSourceInput("https://example.org/plugin.cs3"))
        assertEquals(SourceInputIntent.SOURCE_CONFIG_FILE, detectSourceInput("/sdcard/Download/mine.json"))
    }

    @Test
    fun `an unread http json stays ambiguous instead of guessing`() {
        assertEquals(SourceInputIntent.JSON_ADDRESS, detectSourceInput("https://example.org/data.json"))
        assertEquals(SourceInputIntent.JSON_ADDRESS, detectSourceInput("https://example.org/data.json?raw=1"))
        assertEquals(SourceInputIntent.UNKNOWN, detectSourceInput(""))
        assertEquals(SourceInputIntent.UNKNOWN, detectSourceInput("   "))
        assertEquals(SourceInputIntent.UNKNOWN, detectSourceInput("https://example.org/page.html"))
        assertEquals(SourceInputIntent.UNKNOWN, detectSourceInput("随手的文本"))
    }

    @Test
    fun `query and fragment do not hide the file extension`() {
        assertEquals(
            SourceInputIntent.EXTENSION_FILE,
            detectSourceInput("https://example.org/download/ext.jar?token=abc"),
        )
        assertEquals(
            SourceInputIntent.REPOSITORY_INDEX,
            detectSourceInput("https://example.org/index.min.json#latest"),
        )
    }

    @Test
    fun `a fetched body tells a repository index from a source catalogue`() {
        assertEquals(
            SourceConfigFormat.REPOSITORY_INDEX,
            sniffSourceConfigFormat("""{"name":"repo","packages":[{"name":"a","url":"x.jar"}]}"""),
        )
        assertEquals(
            SourceConfigFormat.TVBOX_CMS,
            sniffSourceConfigFormat("""{"sites":[{"key":"a","api":"https://example.org/api.php/provide/vod"}]}"""),
        )
        assertEquals(
            SourceConfigFormat.LEGADO,
            sniffSourceConfigFormat("""{"bookSourceUrl":"https://x","ruleSearch":"//div"}"""),
        )
        assertEquals(SourceConfigFormat.UNKNOWN, sniffSourceConfigFormat("""{"hello":"world"}"""))
        assertEquals(SourceConfigFormat.UNKNOWN, sniffSourceConfigFormat("not json at all"))
        assertEquals(SourceConfigFormat.UNKNOWN, sniffSourceConfigFormat("   "))
    }

    @Test
    fun `a bare array is told apart by its entries`() {
        assertEquals(
            SourceConfigFormat.LEGADO,
            sniffSourceConfigFormat("""[{"bookSourceUrl":"https://x"},{"bookSourceUrl":"https://y"}]"""),
        )
        assertEquals(
            SourceConfigFormat.TVBOX_CMS,
            sniffSourceConfigFormat("""[{"key":"a","api":"https://example.org/api.php/provide/vod","searchable":1}]"""),
        )
        assertEquals(SourceConfigFormat.UNKNOWN, sniffSourceConfigFormat("""["just","names"]"""))
    }

    @Test
    fun `nested members never count as top level keys`() {
        assertEquals(
            SourceConfigFormat.UNKNOWN,
            sniffSourceConfigFormat("""{"meta":{"sites":[{"api":"x"}]}}"""),
        )
        // Braces and braces-looking text inside strings must not move the nesting depth.
        assertEquals(
            SourceConfigFormat.REPOSITORY_INDEX,
            sniffSourceConfigFormat("""{"note":"{ not a brace }","packages":[]}"""),
        )
        assertEquals(
            SourceConfigFormat.REPOSITORY_INDEX,
            sniffSourceConfigFormat("""{"manifest_version":1,"packages":[]}"""),
        )
    }

    @Test
    fun `a preview counts the entries the body declares`() {
        assertEquals(2, countSourceEntries("""{"packages":[{"name":"a"},{"name":"b"}]}"""))
        assertEquals(1, countSourceEntries("""{"packages":[{"name":"a","meta":{"x":1}}],"name":"repo"}"""))
        assertEquals(0, countSourceEntries("""{"packages":[]}"""))
        assertEquals(2, countSourceEntries("""{"sites":[{"key":"a"},{"key":"b"}]}""", key = "sites"))
        assertEquals(0, countSourceEntries("""{"sites":[{"key":"a"}]}"""))
        // Nested keys do not count, and a truncated body must not throw.
        assertEquals(0, countSourceEntries("""{"meta":{"packages":[{}]}}"""))
        // A truncated body reports what it could read instead of throwing.
        assertEquals(1, countSourceEntries("""{"packages":[{"name":"a"}"""))
        assertEquals(0, countSourceEntries("<html></html>"))
    }

    @Test
    fun `a custom package key still reads as a repository`() {
        assertEquals(
            SourceConfigFormat.REPOSITORY_INDEX,
            sniffSourceConfigFormat("""{"plugins":[{"url":"a.jar"}]}""", knownPackagesKey = "plugins"),
        )
    }
}
