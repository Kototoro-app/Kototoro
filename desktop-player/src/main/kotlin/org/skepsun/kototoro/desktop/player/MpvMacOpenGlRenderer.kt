package org.skepsun.kototoro.desktop.player

import com.sun.jna.Callback
import com.sun.jna.Library
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.NativeLibrary
import com.sun.jna.Pointer
import com.sun.jna.Structure
import com.sun.jna.ptr.IntByReference
import com.sun.jna.ptr.PointerByReference
import java.awt.image.BufferedImage
import java.awt.image.DataBufferInt

/** CGL is owned here, independently of Compose/Skiko's graphics context. No rendering runs on the AWT thread. */
internal class MpvMacOpenGlRenderer(private val mpv: MpvNative, handle: Pointer) : MpvFrameRenderer {
    private val gl = MacOpenGl.instance
    private val cgl: Pointer
    private val context: Pointer
    // Keep the callback strongly reachable for the complete libmpv renderer lifetime.
    private val resolver = GlResolver { _, name ->
        runCatching { MacOpenGl.library.getFunction(name) as Pointer }.getOrNull()
    }
    private var closed = false
    private var framebuffer = 0
    private var texture = 0
    private var width = 0
    private var height = 0
    private var pixels: Memory? = null

    init {
        val format = PointerByReference()
        val count = IntByReference()
        // OpenGL 4.1 core, accelerated renderer, 24-bit colour and 8-bit alpha.
        checkCgl(gl.CGLChoosePixelFormat(intArrayOf(99, 0x4100, 73, 8, 24, 11, 8, 0), format, count))
        val chosen = requireNotNull(format.value) { "No accelerated CGL pixel format" }
        val output = PointerByReference()
        try { checkCgl(gl.CGLCreateContext(chosen, null, output)) }
        finally { gl.CGLDestroyPixelFormat(chosen) }
        cgl = requireNotNull(output.value)
        try {
            context = current {
                val init = GlInitParams().apply { getProcAddress = resolver; write() }
                Memory(7).use { api ->
                    api.setString(0, "opengl")
                    val params = mpvRenderParameters(1 to api, 2 to init.pointer)
                    val reference = PointerByReference()
                    val code = mpv.mpv_render_context_create(reference, handle, params.pointer)
                    if (code < 0) throw MpvException("mpv OpenGL create: ${mpv.mpv_error_string(code)}")
                    requireNotNull(reference.value)
                }
            }
        } catch (error: Throwable) {
            gl.CGLDestroyContext(cgl)
            throw error
        }
    }

    @Synchronized
    override fun frame(width: Int, height: Int): BufferedImage {
        check(!closed) { "Renderer is closed" }
        // Video frames follow the window; Anime4K pages ([MpvImageEnhancer]) reach 6000×8400, within GL's 16384 limit.
        require(width in 1..16384 && height in 1..16384)
        return current {
            if (width != this.width || height != this.height) resize(width, height)
            Memory(16).use { fbo ->
                fbo.setInt(0, framebuffer)
                fbo.setInt(4, width)
                fbo.setInt(8, height)
                fbo.setInt(12, 0x8058) // GL_RGBA8
                Memory(4).use { flip ->
                    flip.setInt(0, 0) // mpv's unflipped FBO rows match the top-first image readback convention.
                    val params = mpvRenderParameters(3 to fbo, 4 to flip)
                    val code = mpv.mpv_render_context_render(context, params.pointer)
                    if (code < 0) throw MpvException("mpv OpenGL render: ${mpv.mpv_error_string(code)}")
                }
            }
            gl.glBindFramebuffer(0x8D40, framebuffer)
            val buffer = requireNotNull(pixels)
            gl.glReadPixels(0, 0, width, height, 0x80E1, 0x8367, buffer) // BGRA, UNSIGNED_INT_8_8_8_8_REV
            gl.glBindFramebuffer(0x8D40, 0)
            BufferedImage(width, height, BufferedImage.TYPE_INT_RGB).also { image ->
                val data = (image.raster.dataBuffer as DataBufferInt).data
                buffer.read(0, data, 0, data.size)
            }
        }
    }

    private fun resize(width: Int, height: Int) {
        releaseFramebuffer()
        val id = IntArray(1)
        gl.glGenTextures(1, id)
        texture = id[0]
        gl.glBindTexture(0x0DE1, texture)
        gl.glTexParameteri(0x0DE1, 0x2801, 0x2601) // MIN_FILTER = LINEAR
        gl.glTexParameteri(0x0DE1, 0x2800, 0x2601)
        gl.glTexImage2D(0x0DE1, 0, 0x8058, width, height, 0, 0x1908, 0x1401, null)
        gl.glGenFramebuffers(1, id)
        framebuffer = id[0]
        gl.glBindFramebuffer(0x8D40, framebuffer)
        gl.glFramebufferTexture2D(0x8D40, 0x8CE0, 0x0DE1, texture, 0)
        check(gl.glCheckFramebufferStatus(0x8D40) == 0x8CD5) { "CGL framebuffer is incomplete" }
        gl.glBindTexture(0x0DE1, 0)
        gl.glBindFramebuffer(0x8D40, 0)
        pixels = Memory(width.toLong() * height * 4)
        this.width = width
        this.height = height
    }

    private fun releaseFramebuffer() {
        if (framebuffer != 0) gl.glDeleteFramebuffers(1, intArrayOf(framebuffer))
        if (texture != 0) gl.glDeleteTextures(1, intArrayOf(texture))
        framebuffer = 0
        texture = 0
        width = 0
        height = 0
        pixels?.close()
        pixels = null
    }

    private fun <T> current(block: () -> T): T {
        val previous = gl.CGLGetCurrentContext()
        checkCgl(gl.CGLSetCurrentContext(cgl))
        try { return block() } finally { checkCgl(gl.CGLSetCurrentContext(previous)) }
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        try {
            current {
                try { mpv.mpv_render_context_free(context) } finally { releaseFramebuffer() }
            }
        } finally { gl.CGLDestroyContext(cgl) }
    }

    private fun checkCgl(code: Int) {
        if (code != 0) throw MpvException("CGL error $code")
    }
}

internal fun interface GlResolver : Callback {
    fun invoke(context: Pointer?, name: String): Pointer?
}

@Structure.FieldOrder("getProcAddress", "context")
internal class GlInitParams : Structure() {
    @JvmField var getProcAddress: GlResolver? = null
    @JvmField var context: Pointer? = null
}

@Suppress("FunctionName")
internal interface MacOpenGl : Library {
    fun CGLChoosePixelFormat(attributes: IntArray, format: PointerByReference, count: IntByReference): Int
    fun CGLDestroyPixelFormat(format: Pointer): Int
    fun CGLCreateContext(format: Pointer, share: Pointer?, context: PointerByReference): Int
    fun CGLDestroyContext(context: Pointer): Int
    fun CGLGetCurrentContext(): Pointer?
    fun CGLSetCurrentContext(context: Pointer?): Int
    fun glGenTextures(count: Int, names: IntArray)
    fun glDeleteTextures(count: Int, names: IntArray)
    fun glBindTexture(target: Int, name: Int)
    fun glTexParameteri(target: Int, name: Int, value: Int)
    fun glTexImage2D(target: Int, level: Int, format: Int, width: Int, height: Int, border: Int,
        pixelFormat: Int, type: Int, data: Pointer?)
    fun glGenFramebuffers(count: Int, names: IntArray)
    fun glDeleteFramebuffers(count: Int, names: IntArray)
    fun glBindFramebuffer(target: Int, name: Int)
    fun glFramebufferTexture2D(target: Int, attachment: Int, textureTarget: Int, texture: Int, level: Int)
    fun glCheckFramebufferStatus(target: Int): Int
    fun glReadPixels(x: Int, y: Int, width: Int, height: Int, format: Int, type: Int, output: Pointer)

    companion object {
        private const val PATH = "/System/Library/Frameworks/OpenGL.framework/OpenGL"
        val library: NativeLibrary by lazy { NativeLibrary.getInstance(PATH) }
        val instance: MacOpenGl by lazy { Native.load(PATH, MacOpenGl::class.java) }
    }
}
