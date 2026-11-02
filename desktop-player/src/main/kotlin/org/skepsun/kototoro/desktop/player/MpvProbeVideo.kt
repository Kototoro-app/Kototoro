package org.skepsun.kototoro.desktop.player

import java.nio.file.Files
import java.nio.file.Path

/** A local, seekable color fixture; requires no FFmpeg encoders or virtual capture devices. */
object MpvProbeVideo {
    fun write(path: Path, seconds: Int = 10): Path {
        require(seconds in 1..60)
        val width = 64
        val height = 48
        val frame = ByteArray(width * height * 3 / 2)
        for (y in 0 until height) {
            for (x in 0 until width) frame[y * width + x] = (if (y < height / 2) 81 else 41).toByte()
        }
        val chromaSize = width * height / 4
        for (index in 0 until chromaSize) {
            val top = index < chromaSize / 2
            frame[width * height + index] = (if (top) 90 else 240).toByte()
            frame[width * height + chromaSize + index] = (if (top) 240 else 110).toByte()
        }
        Files.newOutputStream(path).buffered().use { output ->
            output.write("YUV4MPEG2 W64 H48 F10:1 Ip A1:1 C420jpeg\n".toByteArray(Charsets.US_ASCII))
            repeat(seconds * 10) {
                output.write("FRAME\n".toByteArray(Charsets.US_ASCII))
                output.write(frame)
            }
        }
        return path
    }
}
