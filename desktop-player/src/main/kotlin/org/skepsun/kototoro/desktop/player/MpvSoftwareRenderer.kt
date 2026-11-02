package org.skepsun.kototoro.desktop.player

import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.ptr.PointerByReference
import java.awt.image.BufferedImage
import java.awt.image.DataBufferInt

/** libmpv's supported software render API avoids treating a macOS NSView pointer as an mpv window ID. */
internal class MpvSoftwareRenderer(private val native: MpvNative, handle: Pointer) : MpvFrameRenderer {
    private val context: Pointer
    private var closed = false
    private var pixels: Memory? = null

    init {
        val api = Memory(3).apply { setString(0, "sw") }
        val params = mpvRenderParameters(1 to api)
        val reference = PointerByReference()
        val code = native.mpv_render_context_create(reference, handle, params.pointer)
        if (code < 0) throw MpvException("mpv render create: ${native.mpv_error_string(code)}")
        context = requireNotNull(reference.value)
    }

    @Synchronized
    override fun frame(width: Int, height: Int): BufferedImage {
        check(!closed) { "Renderer is closed" }
        require(width in 1..3840 && height in 1..2160)
        val size = Memory(8).apply { setInt(0, width); setInt(4, height) }
        val format = Memory(5).apply { setString(0, "bgr0") }
        val stride = Memory(Native.SIZE_T_SIZE.toLong()).apply {
            if (Native.SIZE_T_SIZE == 8) setLong(0, width * 4L) else setInt(0, width * 4)
        }
        val byteCount = width.toLong() * height * 4
        if (pixels?.size() != byteCount) {
            pixels?.close()
            pixels = null
            pixels = Memory(byteCount)
        }
        val buffer = requireNotNull(pixels)
        try {
            val params = mpvRenderParameters(17 to size, 18 to format, 19 to stride, 20 to buffer)
            val code = native.mpv_render_context_render(context, params.pointer)
            if (code < 0) throw MpvException("mpv render: ${native.mpv_error_string(code)}")
            return BufferedImage(width, height, BufferedImage.TYPE_INT_RGB).also { image ->
                val data = (image.raster.dataBuffer as DataBufferInt).data
                buffer.read(0, data, 0, data.size)
            }
        } finally {
            size.close()
            format.close()
            stride.close()
        }
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        native.mpv_render_context_free(context)
        pixels?.close()
        pixels = null
    }

}
