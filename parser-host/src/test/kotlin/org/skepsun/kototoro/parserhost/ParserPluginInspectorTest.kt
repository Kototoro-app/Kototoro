package org.skepsun.kototoro.parserhost

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class ParserPluginInspectorTest {
    @TempDir lateinit var root: Path
    private val inspector = ParserPluginInspector()

    @Test
    fun `detects each architecture from its factory entry without loading classes`() {
        val kototoro = inspector.inspect(Fixtures.kototoro)
        val kotatsu = inspector.inspect(Fixtures.kotatsu)
        val tsuki = inspector.inspect(Fixtures.tsuki)
        assertEquals(ParserPluginArchitecture.KOTOTORO, kototoro.architecture)
        assertEquals(ParserPluginArchitecture.KOTATSU, kotatsu.architecture)
        assertEquals(ParserPluginArchitecture.TSUKI, tsuki.architecture)
        assertTrue(listOf(kototoro, kotatsu, tsuki).all { it.classCount > 0 && it.maximumClassVersion in 52..70 })
        assertTrue(kototoro.sha256.matches(Regex("[0-9a-f]{64}")))
    }

    @Test
    fun `an Android dex only jar is refused with its own failure`() {
        val dex = Fixtures.jar(root.resolve("plugin.jar"), mapOf("classes.dex" to byteArrayOf(0x64, 0x65, 0x78, 0x0a)))
        assertEquals(ParserPluginFailure.DEX_ONLY, assertThrows<ParserPluginException> { inspector.inspect(dex) }.failure)
    }

    @Test
    fun `an archive without class files or an unknown architecture is refused`() {
        val empty = Fixtures.jar(root.resolve("empty.jar"), mapOf("readme.txt" to ByteArray(1)))
        assertEquals(ParserPluginFailure.INVALID_ARCHIVE, assertThrows<ParserPluginException> { inspector.inspect(empty) }.failure)
        val unrelated = Fixtures.jar(
            root.resolve("unrelated.jar"),
            mapOf("x/Y.class" to javaClass.getResourceAsStream("ParserPluginInspectorTest.class")!!.readAllBytes()),
        )
        assertEquals(
            ParserPluginFailure.UNSUPPORTED_ARCHITECTURE,
            assertThrows<ParserPluginException> { inspector.inspect(unrelated) }.failure,
        )
    }

    @Test
    fun `bytecode newer than the running JVM is refused`() {
        val future = Fixtures.jar(
            root.resolve("future.jar"),
            mapOf("org/koitharu/kotatsu/parsers/MangaParserFactoryKt.class" to
                byteArrayOf(0xCA.toByte(), 0xFE.toByte(), 0xBA.toByte(), 0xBE.toByte(), 0, 0, 0, 120)),
        )
        assertEquals(ParserPluginFailure.UNSUPPORTED_BYTECODE, assertThrows<ParserPluginException> { inspector.inspect(future) }.failure)
    }

    @Test
    fun `a garbage file is an invalid archive and the hash must match when it is expected`() {
        val garbage = root.resolve("garbage.jar").also { java.nio.file.Files.write(it, ByteArray(32) { 7 }) }
        assertEquals(ParserPluginFailure.INVALID_ARCHIVE, assertThrows<ParserPluginException> { inspector.inspect(garbage) }.failure)
        val metadata = inspector.inspect(Fixtures.kotatsu)
        inspector.verify(metadata, metadata.sha256.uppercase())
        assertEquals(
            ParserPluginFailure.HASH_MISMATCH,
            assertThrows<ParserPluginException> { inspector.verify(metadata, "0".repeat(64)) }.failure,
        )
        assertEquals(
            ParserPluginFailure.HASH_MISMATCH,
            assertThrows<ParserPluginException> { inspector.verify(metadata, "short") }.failure,
        )
    }
}
