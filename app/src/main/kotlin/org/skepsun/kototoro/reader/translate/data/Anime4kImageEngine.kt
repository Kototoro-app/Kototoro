package org.skepsun.kototoro.reader.translate.data

import android.content.Context
import android.graphics.Bitmap
import android.opengl.GLES30
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.skepsun.kototoro.core.opengl.EglCore
import org.skepsun.kototoro.core.opengl.GlUtil
import org.skepsun.kototoro.core.opengl.OffscreenSurface
import java.io.File
import java.nio.ByteBuffer

class Anime4kImageEngine(private val context: Context) {
    private val TAG = "Anime4kImageEngine"

    private var eglCore: EglCore? = null
    private var surface: OffscreenSurface? = null
    private var isInitialized = false

    private val passes = mutableListOf<Anime4kPass>()
    private val programs = mutableListOf<Int>()

    private val vertexShaderCode = """
        #version 300 es
        layout(location = 0) in vec4 aPosition;
        layout(location = 1) in vec2 aTexCoord;
        out vec2 vTexCoord;
        void main() {
            gl_Position = aPosition;
            vTexCoord = aTexCoord;
        }
    """.trimIndent()

    suspend fun initialize(shadersDir: File, presetShaders: List<String>): Boolean = withContext(Dispatchers.Default) {
        if (isInitialized) return@withContext true

        try {
            val sources = presetShaders.map { shaderFile ->
                File(shadersDir, shaderFile).readText()
            }
            passes.addAll(Anime4kCompiler.parse(sources))

            eglCore = EglCore()
            // create a dummy 1x1 surface just to have a current context
            surface = OffscreenSurface(eglCore!!, 1, 1).apply { makeCurrent() }

            // Compile shaders
            for (pass in passes) {
                val fSource = Anime4kCompiler.compileToFragmentShader(pass)
                val prog = GlUtil.createProgram(vertexShaderCode, fSource)
                if (prog == 0) throw RuntimeException("Failed to compile pass: ${pass.desc}")
                programs.add(prog)
            }

            isInitialized = true
            true
        } catch (e: Exception) {
            Log.e(TAG, "Initialization failed", e)
            release()
            false
        } finally {
            eglCore?.makeNothingCurrent()
        }
    }

    suspend fun process(inBitmap: Bitmap): Bitmap? = withContext(Dispatchers.Default) {
        if (!isInitialized) return@withContext null

        surface?.makeCurrent()

        var currentOutputTex = 0
        var currentWidth = inBitmap.width
        var currentHeight = inBitmap.height

        val textureSizes = mutableMapOf<String, Pair<Int, Int>>()
        textureSizes["MAIN"] = Pair(inBitmap.width, inBitmap.height)
        textureSizes["OUTPUT"] = Pair(inBitmap.width, inBitmap.height)

        val textures = mutableMapOf<String, Int>()
        val fbos = mutableMapOf<String, Int>()

        val allCreatedTextures = mutableListOf<Int>()
        val allCreatedFbos = mutableListOf<Int>()

        val freePoolDims = mutableMapOf<Int, Pair<Int, Int>>()
        val freePool = mutableListOf<Int>()

        fun acquireTextureAndFbo(w: Int, h: Int): Pair<Int, Int> {
            val texIdx = freePool.indexOfFirst { freePoolDims[it]?.first == w && freePoolDims[it]?.second == h }
            if (texIdx >= 0) {
                val tex = freePool.removeAt(texIdx)
                val fbo = allCreatedFbos[allCreatedTextures.indexOf(tex)]
                GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo)
                return Pair(tex, fbo)
            }
            val tex = GlUtil.createEmptyTexture(w, h)
            allCreatedTextures.add(tex)
            freePoolDims[tex] = Pair(w, h)
            val fbo = IntArray(1).also { GLES30.glGenFramebuffers(1, it, 0) }[0]
            allCreatedFbos.add(fbo)
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo)
            GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, tex, 0)
            val status = GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER)
            if (status != GLES30.GL_FRAMEBUFFER_COMPLETE) {
                throw RuntimeException("FBO incomplete in acquire: \$status")
            }
            return Pair(tex, fbo)
        }

        try {
            val initialMainTex = GlUtil.createTexture(inBitmap)
            textures["MAIN"] = initialMainTex
            allCreatedTextures.add(initialMainTex)
            currentOutputTex = textures["MAIN"]!!

            // Standard fullscreen quad: GLUtils.texImage2D and glReadPixels
            // both invert Y, so the double-inversion cancels out.
            val vboData = floatArrayOf(
                // x, y, u, v
                -1f, -1f, 0f, 0f,
                 1f, -1f, 1f, 0f,
                -1f,  1f, 0f, 1f,
                 1f,  1f, 1f, 1f
            )
            val vboBuffer = GlUtil.createFloatBuffer(vboData)

            for ((i, pass) in passes.withIndex()) {
                // Skip passes that hook textures that don't exist in our pipeline
                // (e.g. PREKERNEL is an mpv-only hook point from Clamp_Highlights)
                if (pass.hook != "MAIN" && !textures.containsKey(pass.hook)) {
                    Log.d(TAG, "Skipping pass ${pass.desc}: hook '${pass.hook}' not available")
                    continue
                }

                val prog = programs[i]
                GLES30.glUseProgram(prog)

                // Calculate pass output dimensions
                val passW = if (pass.widthExpression != null) {
                    Anime4kSizeEvaluator.evaluate(pass.widthExpression, textureSizes)
                } else textureSizes[pass.hook]?.first ?: currentWidth

                val passH = if (pass.heightExpression != null) {
                    Anime4kSizeEvaluator.evaluate(pass.heightExpression, textureSizes)
                } else textureSizes[pass.hook]?.second ?: currentHeight

                currentWidth = passW
                currentHeight = passH

                // Always use a specific output FBO to prevent GL feedback loops (read/write same texture)
                val saveName = pass.save
                // Determine if this is the last *executable* pass (accounting for skipped ones)
                val isLastPass = (i == passes.indices.lastOrNull { idx ->
                    val p = passes[idx]
                    p.hook == "MAIN" || textures.containsKey(p.hook) || (p.save != null && textures.containsKey(p.save))
                })
                val (outTex, outFbo) = if (isLastPass) {
                    val tex = GlUtil.createEmpty8BitTexture(passW, passH)
                    allCreatedTextures.add(tex)
                    val fbo = IntArray(1).also { GLES30.glGenFramebuffers(1, it, 0) }[0]
                    allCreatedFbos.add(fbo)
                    GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo)
                    GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, tex, 0)
                    val status = GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER)
                    if (status != GLES30.GL_FRAMEBUFFER_COMPLETE) {
                        throw RuntimeException("FBO incomplete in last pass: \$status")
                    }
                    Pair(tex, fbo)
                } else {
                    acquireTextureAndFbo(passW, passH)
                }
                currentOutputTex = outTex
                GLES30.glViewport(0, 0, passW, passH)

                // Bind uniforms
                var texUnit = 0
                val binds = mutableListOf("MAIN")
                binds.addAll(pass.binds)
                if (pass.hook != "MAIN") binds.add(pass.hook)

                for (bind in binds.distinct()) {
                    val location = GLES30.glGetUniformLocation(prog, bind)
                    if (location >= 0) {
                        GLES30.glActiveTexture(GLES30.GL_TEXTURE0 + texUnit)
                        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, textures[bind] ?: textures["MAIN"]!!)
                        GLES30.glUniform1i(location, texUnit)

                        val sizeLoc = GLES30.glGetUniformLocation(prog, "${bind}_size")
                        val hw = textureSizes[bind] ?: textureSizes["MAIN"]!!
                        if (sizeLoc >= 0) GLES30.glUniform2f(sizeLoc, hw.first.toFloat(), hw.second.toFloat())

                        val ptLoc = GLES30.glGetUniformLocation(prog, "${bind}_pt")
                        if (ptLoc >= 0) GLES30.glUniform2f(ptLoc, 1.0f / hw.first, 1.0f / hw.second)

                        texUnit++
                    }
                }

                // Draw quad
                val posLoc = GLES30.glGetAttribLocation(prog, "aPosition")
                val texLoc = GLES30.glGetAttribLocation(prog, "aTexCoord")

                GLES30.glEnableVertexAttribArray(posLoc)
                GLES30.glEnableVertexAttribArray(texLoc)

                vboBuffer.position(0)
                GLES30.glVertexAttribPointer(posLoc, 2, GLES30.GL_FLOAT, false, 4 * 4, vboBuffer)
                vboBuffer.position(2)
                GLES30.glVertexAttribPointer(texLoc, 2, GLES30.GL_FLOAT, false, 4 * 4, vboBuffer)

                GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)

                GLES30.glDisableVertexAttribArray(posLoc)
                GLES30.glDisableVertexAttribArray(texLoc)

                // Now safely update the texture map and sizes
                if (saveName != null) {
                    textures[saveName] = outTex
                    textureSizes[saveName] = Pair(passW, passH)
                    fbos[saveName] = outFbo
                } else {
                    textures["MAIN"] = outTex
                    textureSizes["MAIN"] = Pair(passW, passH)
                    fbos["MAIN"] = outFbo
                }

                // Recycle orphaned FBOs instead of deleting them to prevent VRAM allocation thrashing
                val activeTex = textures.values.toSet()
                val orphanedTex = allCreatedTextures.filter {
                    it !in activeTex && it !in freePool && freePoolDims.containsKey(it)
                }
                freePool.addAll(orphanedTex)
            }

            // Readback: read RGBA bytes, convert to ARGB ints for Bitmap.setPixels()
            val buffer = ByteBuffer.allocateDirect(currentWidth * currentHeight * 4)
            GLES30.glReadPixels(0, 0, currentWidth, currentHeight, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, buffer)
            buffer.position(0)

            val pixels = IntArray(currentWidth * currentHeight)
            for (j in pixels.indices) {
                val r = buffer.get().toInt() and 0xFF
                val g = buffer.get().toInt() and 0xFF
                val b = buffer.get().toInt() and 0xFF
                buffer.get() // skip alpha
                pixels[j] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }

            val outBmp = Bitmap.createBitmap(currentWidth, currentHeight, Bitmap.Config.ARGB_8888)
            outBmp.setPixels(pixels, 0, currentWidth, 0, 0, currentWidth, currentHeight)

            return@withContext outBmp
        } catch (e: Exception) {
            Log.e(TAG, "Process failed", e)
            return@withContext null
        } finally {
            if (allCreatedFbos.isNotEmpty()) {
                GLES30.glDeleteFramebuffers(allCreatedFbos.size, allCreatedFbos.toIntArray(), 0)
            }
            if (allCreatedTextures.isNotEmpty()) {
                GLES30.glDeleteTextures(allCreatedTextures.size, allCreatedTextures.toIntArray(), 0)
            }
            eglCore?.makeNothingCurrent()
        }
    }

    fun release() {
        surface?.release()
        surface = null
        for (prog in programs) {
            GLES30.glDeleteProgram(prog)
        }
        programs.clear()
        eglCore?.release()
        eglCore = null
        isInitialized = false
    }
}
