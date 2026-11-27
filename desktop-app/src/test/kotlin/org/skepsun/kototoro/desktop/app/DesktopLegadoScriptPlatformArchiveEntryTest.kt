package org.skepsun.kototoro.desktop.app

import okhttp3.OkHttpClient
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.skepsun.kototoro.core.source.SourcePreferenceEdit
import org.skepsun.kototoro.core.source.SourcePreferenceListener
import org.skepsun.kototoro.core.source.SourcePreferences
import org.skepsun.kototoro.core.source.SourcePreferenceValue
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class DesktopLegadoScriptPlatformArchiveEntryTest {

    @TempDir
    lateinit var root: Path

    @Test
    fun `the requested archive entry is returned unchanged`() {
        val payload = """{"bookSourceUrl":"https://example.org"}""".repeat(50).toByteArray()
        val archive = zipOf("other.txt" to ByteArray(16), "source.json" to payload)

        val bytes = platform().archiveEntry(ByteArrayInputStream(archive), "source.json")

        assertArrayEquals(payload, bytes)
    }

    @Test
    fun `a missing archive entry still returns null`() {
        val archive = zipOf("source.json" to ByteArray(16))

        assertNull(platform().archiveEntry(ByteArrayInputStream(archive), "absent.json"))
    }

    @Test
    fun `an archive entry past the shared limit is refused instead of being read whole`() {
        // Decompresses to the limit plus one byte, but stays tiny on disk while zipped.
        val archive = zipOf("bomb.txt" to ByteArray((64L * 1024 * 1024 + 1).toInt()))

        val error = assertThrows(IOException::class.java) {
            platform().archiveEntry(ByteArrayInputStream(archive), "bomb.txt")
        }

        assertEquals(
            "Archive entry too large: bomb.txt (limit ${64L * 1024 * 1024} bytes)",
            error.message,
        )
    }

    private fun platform() = DesktopLegadoScriptPlatform(
        client = OkHttpClient(),
        root = root,
        stores = { EmptySourcePreferences },
    )

    private fun zipOf(vararg entries: Pair<String, ByteArray>): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            entries.forEach { (name, content) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(content)
                zip.closeEntry()
            }
        }
        return output.toByteArray()
    }

    private object EmptySourcePreferences : SourcePreferences {
        override fun snapshot(): Map<String, SourcePreferenceValue> = emptyMap()
        override fun edit(edit: SourcePreferenceEdit): Boolean = true
        override fun addListener(listener: SourcePreferenceListener) = Unit
        override fun removeListener(listener: SourcePreferenceListener) = Unit
    }
}
