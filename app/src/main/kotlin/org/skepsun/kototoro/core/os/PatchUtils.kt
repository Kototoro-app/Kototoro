package org.skepsun.kototoro.core.os

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.File
import java.io.InputStream
import java.io.RandomAccessFile

object PatchUtils {

    /**
     * Applies a bspatch to [oldFile] using [patchFile] to generate [newFile].
     * This is a pure Kotlin implementation of the standard bsdiff4/bspatch algorithm.
     */
    @Suppress("BlockingMethodInNonBlockingContext")
    suspend fun patch(oldFile: File, patchFile: File, newFile: File) = withContext(Dispatchers.IO) {
        val oldRaf = RandomAccessFile(oldFile, "r")
        val patchStream = patchFile.inputStream()
        
        var ctrlStream: InputStream? = null
        var diffStream: InputStream? = null
        var extraStream: InputStream? = null
        
        try {
            // Read the 32-byte header
            val header = ByteArray(32)
            if (patchStream.readWithFully(header, 0, 32) != 32 || String(header, 0, 8) != "BSDIFF40") {
                throw IllegalArgumentException("Invalid patch file (header magic mismatch)")
            }

            val ctrlBlockLen = readOff(header, 8)
            val diffBlockLen = readOff(header, 16)
            val newSize = readOff(header, 24)

            if (ctrlBlockLen < 0 || diffBlockLen < 0 || newSize < 0) {
                throw IllegalArgumentException("Invalid patch file (negative lengths)")
            }

            // Seek to the various blocks inside the patch file
            ctrlStream = BZip2CompressorInputStream(patchFile.inputStream().apply { skip(32) })
            diffStream = BZip2CompressorInputStream(patchFile.inputStream().apply { skip(32 + ctrlBlockLen) })
            extraStream = BZip2CompressorInputStream(patchFile.inputStream().apply { skip(32 + ctrlBlockLen + diffBlockLen) })

            newFile.outputStream().buffered().use { newStream ->
                var oldPos = 0L
                var newPos = 0L
                val ctrlBlock = ByteArray(24)
                val diffBuf = ByteArray(8192)
                val oldBuf = ByteArray(8192)

                while (newPos < newSize) {
                    // Read control data (3 x 8 bytes)
                    val ctrlBytesRead = ctrlStream.readWithFully(ctrlBlock, 0, 24)
                    if (ctrlBytesRead != 24) throw IllegalStateException("Corrupt patch file (EOF in ctrl stream)")
                    
                    val diffStrLen = readOff(ctrlBlock, 0)
                    val extraStrLen = readOff(ctrlBlock, 8)
                    val seekStrLen = readOff(ctrlBlock, 16)

                    if (newPos + diffStrLen > newSize) {
                        throw IllegalStateException("Corrupt patch file (diff larger than remaining size)")
                    }

                    // Read diff block + old file, add them and write
                    var diffRemaining = diffStrLen
                    while (diffRemaining > 0) {
                        val toRead = minOf(diffRemaining, diffBuf.size.toLong()).toInt()
                        val readLen = diffStream.readWithFully(diffBuf, 0, toRead)
                        if (readLen != toRead) {
                            throw IllegalStateException("Corrupt patch file (EOF in diff stream)")
                        }

                        oldRaf.seek(oldPos)
                        var oldReadLen = 0
                        while (oldReadLen < toRead) {
                            // handle EOF in old file padding
                            val r = oldRaf.read(oldBuf, oldReadLen, toRead - oldReadLen)
                            if (r < 0) {
                                for (i in oldReadLen until toRead) {
                                    oldBuf[i] = 0
                                }
                                break
                            }
                            oldReadLen += r
                        }
                        
                        for (i in 0 until toRead) {
                            diffBuf[i] = (diffBuf[i] + oldBuf[i]).toByte()
                        }
                        
                        newStream.write(diffBuf, 0, toRead)
                        diffRemaining -= toRead
                        oldPos += toRead
                        newPos += toRead
                    }

                    if (newPos + extraStrLen > newSize) {
                        throw IllegalStateException("Corrupt patch file (extra larger than remaining size)")
                    }

                    // Read extra block and write
                    var extraRemaining = extraStrLen
                    while (extraRemaining > 0) {
                        val toRead = minOf(extraRemaining, diffBuf.size.toLong()).toInt()
                        val readLen = extraStream.readWithFully(diffBuf, 0, toRead)
                        if (readLen != toRead) {
                            throw IllegalStateException("Corrupt patch file (EOF in extra stream)")
                        }
                        newStream.write(diffBuf, 0, toRead)
                        extraRemaining -= toRead
                        newPos += toRead
                    }

                    oldPos += seekStrLen
                }
            }

        } finally {
            ctrlStream?.close()
            diffStream?.close()
            extraStream?.close()
            oldRaf.close()
            patchStream.close()
        }
    }

    private fun InputStream.readWithFully(b: ByteArray, off: Int, len: Int): Int {
        var n = 0
        while (n < len) {
            val count = read(b, off + n, len - n)
            if (count < 0) return if (n == 0) -1 else n
            n += count
        }
        return n
    }

    /**
     * Read a 64-bit signed integer using the bsdiff off_t encoding.
     */
    private fun readOff(b: ByteArray, offset: Int): Long {
        var y = b[offset + 7].toLong() and 0x7F
        y = (y shl 8) or (b[offset + 6].toLong() and 0xFF)
        y = (y shl 8) or (b[offset + 5].toLong() and 0xFF)
        y = (y shl 8) or (b[offset + 4].toLong() and 0xFF)
        y = (y shl 8) or (b[offset + 3].toLong() and 0xFF)
        y = (y shl 8) or (b[offset + 2].toLong() and 0xFF)
        y = (y shl 8) or (b[offset + 1].toLong() and 0xFF)
        y = (y shl 8) or (b[offset].toLong() and 0xFF)
        if ((b[offset + 7].toInt() and 0x80) != 0) {
            y = -y
        }
        return y
    }
}
