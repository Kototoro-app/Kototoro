package org.skepsun.kototoro.video.player

import android.content.Context
import android.graphics.PixelFormat
import android.graphics.SurfaceTexture
import android.opengl.GLES11Ext
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.util.Log
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.min
import kotlin.math.roundToInt
import org.skepsun.kototoro.core.prefs.Anime4KPreset
import org.skepsun.kototoro.core.prefs.VideoEnhancementAlgorithm

data class VideoEnhancementConfig(
    val algorithm: VideoEnhancementAlgorithm,
    val anime4KPreset: Anime4KPreset,
    val fsrSharpness: Float,
    val sourceWidth: Int,
    val sourceHeight: Int,
)

class EnhancedVideoSurfaceView(context: Context) : GLSurfaceView(context) {
    private var latestVideoSurface: Surface? = null
    private val videoRenderer = VideoEnhancementRenderer(
        context = context.applicationContext,
        onSurfaceReady = { surface ->
            post {
                latestVideoSurface = surface
                surfaceListener?.invoke(surface)
            }
        },
        onFirstFrame = { post { firstFrameListener?.invoke() } },
        onError = { error -> post { errorListener?.invoke(error) } },
        onFrameAvailable = ::requestRender,
    )
    private var surfaceListener: ((Surface?) -> Unit)? = null
    private var firstFrameListener: (() -> Unit)? = null
    private var errorListener: ((Throwable) -> Unit)? = null

    init {
        setEGLContextClientVersion(3)
        setEGLConfigChooser(8, 8, 8, 8, 0, 0)
        holder.setFormat(PixelFormat.TRANSLUCENT)
        setZOrderMediaOverlay(true)
        setRenderer(videoRenderer)
        renderMode = RENDERMODE_WHEN_DIRTY
        preserveEGLContextOnPause = true
    }

    fun setListeners(
        onSurfaceReady: (Surface?) -> Unit,
        onFirstFrame: () -> Unit,
        onError: (Throwable) -> Unit,
    ) {
        surfaceListener = onSurfaceReady
        firstFrameListener = onFirstFrame
        errorListener = onError
        latestVideoSurface?.let { surface -> post { onSurfaceReady(surface) } }
    }

    fun configure(config: VideoEnhancementConfig) {
        queueEvent {
            videoRenderer.configure(config)
            // 配置写入与重绘保持同一 GL 事件顺序，避免 WHEN_DIRTY 请求先于 uniform 更新被消费。
            requestRender()
        }
    }

    fun releaseVideoSurface() {
        queueEvent { videoRenderer.release() }
    }

    fun resumeVideoSurface() {
        onResume()
        queueEvent { videoRenderer.ensureDecoderSurface() }
    }

    fun pauseVideoSurface() {
        queueEvent { videoRenderer.releaseDecoderSurface() }
        onPause()
    }

    override fun onDetachedFromWindow() {
        queueEvent { videoRenderer.release() }
        super.onDetachedFromWindow()
    }
}

private class VideoEnhancementRenderer(
    private val context: Context,
    private val onSurfaceReady: (Surface?) -> Unit,
    private val onFirstFrame: () -> Unit,
    private val onError: (Throwable) -> Unit,
    private val onFrameAvailable: () -> Unit,
) : GLSurfaceView.Renderer, SurfaceTexture.OnFrameAvailableListener {
    private var config = VideoEnhancementConfig(
        algorithm = VideoEnhancementAlgorithm.ANIME4K,
        anime4KPreset = Anime4KPreset.FAST,
        fsrSharpness = 0.9f,
        sourceWidth = 1,
        sourceHeight = 1,
    )
    private var textureId = 0
    private var surfaceTexture: SurfaceTexture? = null
    private var decoderSurface: Surface? = null
    private var externalCopyProgram = 0
    private var displayProgram = 0
    private var fsrEasuProgram = 0
    private var fsrRcasProgram = 0
    private var framePending = false
    private var hasLatchedFrame = false
    private var firstFrameSent = false
    private var outputWidth = 1
    private var outputHeight = 1
    private var maxTextureSize = 1
    private var maxTextureUnits = 1
    private var frameDiagnostics = ""
    private var configRevision = 0L
    private var renderedConfigRevision = -1L
    private var pendingPipelineChange = false
    private var pendingSizeChange = false
    private var nativeTarget: RenderTarget? = null
    private var fsrTarget: RenderTarget? = null
    private val animeTargets = mutableListOf<RenderTarget>()
    private val animePrograms = mutableMapOf<String, Int>()
    private val parsedShaderFiles = mutableMapOf<String, List<Anime4KHookPass>>()
    private val textureMatrix = FloatArray(16)
    private val vertices = ByteBuffer.allocateDirect(16 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
        put(floatArrayOf(-1f, -1f, 0f, 0f, 1f, -1f, 1f, 0f, -1f, 1f, 0f, 1f, 1f, 1f, 1f, 1f))
        position(0)
    }

    fun configure(value: VideoEnhancementConfig) {
        val normalized = value.copy(
            fsrSharpness = value.fsrSharpness.coerceIn(0f, 1f),
            sourceWidth = value.sourceWidth.coerceAtLeast(1),
            sourceHeight = value.sourceHeight.coerceAtLeast(1),
        )
        val pipelineChanged = normalized.algorithm != config.algorithm ||
            normalized.anime4KPreset != config.anime4KPreset
        val sizeChanged = normalized.sourceWidth != config.sourceWidth || normalized.sourceHeight != config.sourceHeight
        val sharpnessChanged = normalized.fsrSharpness != config.fsrSharpness
        if (!pipelineChanged && !sizeChanged && !sharpnessChanged) return
        val previousSharpness = config.fsrSharpness
        config = normalized
        configRevision++
        if (sharpnessChanged) {
            Log.i(
                TAG,
                "FSR sharpness queued from=$previousSharpness to=${config.fsrSharpness} " +
                    "latchedFrame=$hasLatchedFrame revision=$configRevision",
            )
        }
        if (pipelineChanged || sizeChanged) firstFrameSent = false
        // queueEvent can run while GLSurfaceView is resuming but before EGL has made a
        // context current. Defer every GL operation until a renderer callback.
        pendingSizeChange = pendingSizeChange || sizeChanged
        pendingPipelineChange = pendingPipelineChange || pipelineChanged
    }

    override fun onSurfaceCreated(gl: GL10?, eglConfig: EGLConfig?) {
        runCatching {
            releaseGlResources()
            maxTextureSize = IntArray(1).also { GLES30.glGetIntegerv(GLES30.GL_MAX_TEXTURE_SIZE, it, 0) }[0]
            maxTextureUnits = IntArray(1).also {
                GLES30.glGetIntegerv(GLES30.GL_MAX_TEXTURE_IMAGE_UNITS, it, 0)
            }[0]
            textureId = IntArray(1).also { GLES30.glGenTextures(1, it, 0) }[0]
            GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
            GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
            GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
            GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
            GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
            externalCopyProgram = createProgram(VERTEX_SHADER, EXTERNAL_COPY_SHADER, "OES input copy")
            displayProgram = createProgram(VERTEX_SHADER, DISPLAY_SHADER, "display copy")
            fsrEasuProgram = createProgram(VERTEX_SHADER, FSR_EASU_SHADER, "AMD FSR 1.0 EASU")
            fsrRcasProgram = createProgram(VERTEX_SHADER, FSR_RCAS_SHADER, "AMD FSR 1.0 RCAS")
            prepareAnimePrograms()
            pendingPipelineChange = false
            pendingSizeChange = false
            ensureDecoderSurface()
        }.onFailure(onError)
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        outputWidth = width.coerceAtLeast(1)
        outputHeight = height.coerceAtLeast(1)
        releaseRenderTargets()
    }

    override fun onDrawFrame(gl: GL10?) {
        runCatching {
            applyPendingConfigChanges()
            val input = surfaceTexture ?: return
            if (framePending) {
                framePending = false
                input.updateTexImage()
                input.getTransformMatrix(textureMatrix)
                hasLatchedFrame = true
            }
            if (!hasLatchedFrame) {
                GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
                return
            }
            when (config.algorithm) {
                VideoEnhancementAlgorithm.ANIME4K -> renderAnime4K()
                VideoEnhancementAlgorithm.FSR_1_0 -> renderFsr()
            }
            checkGlError("render frame")
            if (renderedConfigRevision != configRevision) {
                renderedConfigRevision = configRevision
                Log.i(
                    TAG,
                    "Enhancement config rendered revision=$configRevision algorithm=${config.algorithm} " +
                        "sharpness=${config.fsrSharpness} latchedFrame=$hasLatchedFrame",
                )
            }
            if (!firstFrameSent) {
                firstFrameSent = true
                Log.i(TAG, "First enhanced frame: $frameDiagnostics")
                onFirstFrame()
            }
        }.onFailure(onError)
    }

    private fun applyPendingConfigChanges() {
        if (!pendingPipelineChange && !pendingSizeChange) return
        if (pendingSizeChange) {
            surfaceTexture?.setDefaultBufferSize(config.sourceWidth, config.sourceHeight)
        }
        releaseRenderTargets()
        if (pendingPipelineChange) prepareAnimePrograms()
        pendingPipelineChange = false
        pendingSizeChange = false
    }

    override fun onFrameAvailable(surfaceTexture: SurfaceTexture?) {
        framePending = true
        onFrameAvailable()
    }

    fun ensureDecoderSurface() {
        if (decoderSurface != null || textureId == 0) return
        surfaceTexture = SurfaceTexture(textureId).also {
            it.setDefaultBufferSize(config.sourceWidth, config.sourceHeight)
            it.setOnFrameAvailableListener(this)
        }
        decoderSurface = Surface(surfaceTexture).also(onSurfaceReady)
    }

    fun releaseDecoderSurface() {
        if (decoderSurface != null) onSurfaceReady(null)
        decoderSurface?.release()
        decoderSurface = null
        surfaceTexture?.release()
        surfaceTexture = null
        framePending = false
        hasLatchedFrame = false
    }

    private fun renderAnime4K() {
        val sourceSize = sourceSize()
        val native = obtainNativeTarget(sourceSize)
        renderExternalToTarget(native, externalCopyProgram)
        val textures = mutableMapOf(
            "NATIVE" to native,
            "MAIN" to native,
        )
        var executed = 0
        for (fileName in shaderFilesFor(config.anime4KPreset)) {
            val filePasses = loadShaderPasses(fileName)
            for ((passIndex, pass) in filePasses.withIndex()) {
                val hookName = if (pass.hook == "PREKERNEL") "MAIN" else pass.hook
                val hooked = textures[hookName] ?: textures["MAIN"] ?: continue
                val sizes = textures.mapValues { ShaderTextureSize(it.value.width, it.value.height) }
                val outputSize = ShaderTextureSize(outputWidth, outputHeight)
                val condition = Anime4KHookExpression.evaluate(pass.conditionExpression, sizes, outputSize)
                if (condition != null && condition == 0.0) continue
                val width = resolvePassDimension(pass.widthExpression, hooked.width, sizes, outputSize)
                val height = resolvePassDimension(pass.heightExpression, hooked.height, sizes, outputSize)
                val bindings = linkedMapOf<String, RenderTarget>()
                bindings["HOOKED"] = hooked
                textures["MAIN"]?.let { bindings["MAIN"] = it }
                for (name in pass.bindings) {
                    bindings[name] = when (name) {
                        "HOOKED" -> hooked
                        "NATIVE" -> native
                        else -> textures[name] ?: error("Missing Anime4K binding $name for ${pass.description}")
                    }
                }
                check(bindings.size <= maxTextureUnits) {
                    "Anime4K pass ${pass.description} needs ${bindings.size} texture units, " +
                        "device supports $maxTextureUnits"
                }
                val protectedTextures = buildSet {
                    add(native.textureId)
                    textures.values.forEach { add(it.textureId) }
                    bindings.values.forEach { add(it.textureId) }
                }
                val target = obtainAnimeTarget(width, height, pass.components, protectedTextures)
                val programKey = "$fileName:$passIndex"
                val program = animePrograms.getOrPut(programKey) {
                    createProgram(VERTEX_SHADER, buildAnimeFragmentShader(pass, bindings.keys), pass.description)
                }
                renderTexturePass(program, bindings, target)
                textures[pass.save ?: hookName] = target
                if (pass.save == null || pass.save == "MAIN") textures["MAIN"] = target
                executed++
            }
            filePasses.mapNotNull(Anime4KHookPass::save)
                .filterNot { it == "MAIN" }
                .forEach(textures::remove)
        }
        displayTexture(textures["MAIN"] ?: native)
        frameDiagnostics =
            "algorithm=Anime4K preset=${config.anime4KPreset} source=${sourceSize.width}x${sourceSize.height} " +
                "output=${outputWidth}x$outputHeight passes=$executed"
    }

    private fun renderFsr() {
        val source = sourceSize()
        val scale = min(
            min(outputWidth.toFloat() / source.width, outputHeight.toFloat() / source.height),
            2f,
        ).coerceAtLeast(1f)
        val targetWidth = (source.width * scale).roundToInt().coerceIn(1, maxTextureSize)
        val targetHeight = (source.height * scale).roundToInt().coerceIn(1, maxTextureSize)
        val easuTarget = obtainFsrTarget(targetWidth, targetHeight)
        bindTarget(easuTarget)
        useQuad(fsrEasuProgram)
        bindExternalTexture(fsrEasuProgram)
        GLES30.glUniformMatrix4fv(uniform(fsrEasuProgram, "uTexMatrix"), 1, false, textureMatrix, 0)
        GLES30.glUniform2f(uniform(fsrEasuProgram, "uInputSize"), source.width.toFloat(), source.height.toFloat())
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)

        bindDisplayViewport(source)
        useQuad(fsrRcasProgram)
        bindTexture2D(fsrRcasProgram, "uTexture", easuTarget.textureId, 0)
        GLES30.glUniform2f(
            uniform(fsrRcasProgram, "uTextureSize"),
            targetWidth.toFloat(),
            targetHeight.toFloat(),
        )
        GLES30.glUniform1f(uniform(fsrRcasProgram, "uSharpnessStops"), 2f * (1f - config.fsrSharpness))
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
        frameDiagnostics =
            "algorithm=FSR_1_0 source=${source.width}x${source.height} easu=${targetWidth}x$targetHeight " +
                "output=${outputWidth}x$outputHeight sharpness=${config.fsrSharpness}"
    }

    private fun renderExternalToTarget(target: RenderTarget, program: Int) {
        bindTarget(target)
        useQuad(program)
        bindExternalTexture(program)
        GLES30.glUniformMatrix4fv(uniform(program, "uTexMatrix"), 1, false, textureMatrix, 0)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
    }

    private fun renderTexturePass(program: Int, bindings: Map<String, RenderTarget>, target: RenderTarget) {
        bindTarget(target)
        useQuad(program)
        bindings.entries.forEachIndexed { index, (name, texture) ->
            bindTexture2D(program, "uTexture_$name", texture.textureId, index)
            GLES30.glUniform2f(
                uniform(program, "uSize_$name"),
                texture.width.toFloat(),
                texture.height.toFloat(),
            )
        }
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
    }

    private fun displayTexture(texture: RenderTarget) {
        bindDisplayViewport(ShaderTextureSize(texture.width, texture.height))
        useQuad(displayProgram)
        bindTexture2D(displayProgram, "uTexture", texture.textureId, 0)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
    }

    private fun bindDisplayViewport(content: ShaderTextureSize) {
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        GLES30.glViewport(0, 0, outputWidth, outputHeight)
        GLES30.glClearColor(0f, 0f, 0f, 1f)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
        val scale = min(outputWidth.toFloat() / content.width, outputHeight.toFloat() / content.height)
        val width = (content.width * scale).roundToInt()
        val height = (content.height * scale).roundToInt()
        GLES30.glViewport((outputWidth - width) / 2, (outputHeight - height) / 2, width, height)
    }

    private fun bindTarget(target: RenderTarget) {
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, target.framebufferId)
        GLES30.glViewport(0, 0, target.width, target.height)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
    }

    private fun useQuad(program: Int) {
        GLES30.glUseProgram(program)
        vertices.position(0)
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, 16, vertices)
        vertices.position(2)
        GLES30.glEnableVertexAttribArray(1)
        GLES30.glVertexAttribPointer(1, 2, GLES30.GL_FLOAT, false, 16, vertices)
    }

    private fun bindExternalTexture(program: Int) {
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        GLES30.glUniform1i(uniform(program, "uTexture"), 0)
    }

    private fun bindTexture2D(program: Int, uniformName: String, texture: Int, unit: Int) {
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0 + unit)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texture)
        GLES30.glUniform1i(uniform(program, uniformName), unit)
    }

    private fun sourceSize(): ShaderTextureSize {
        val width = config.sourceWidth.coerceAtLeast(1)
        val height = config.sourceHeight.coerceAtLeast(1)
        val scale = min(1f, min(maxTextureSize.toFloat() / width, maxTextureSize.toFloat() / height))
        return ShaderTextureSize(
            (width * scale).roundToInt().coerceAtLeast(1),
            (height * scale).roundToInt().coerceAtLeast(1),
        )
    }

    private fun resolvePassDimension(
        expression: String?,
        fallback: Int,
        textures: Map<String, ShaderTextureSize>,
        output: ShaderTextureSize,
    ): Int = Anime4KHookExpression.evaluate(expression, textures, output)
        ?.roundToInt()
        ?.coerceIn(1, maxTextureSize)
        ?: fallback

    private fun shaderFilesFor(preset: Anime4KPreset): List<String> = when (preset) {
        Anime4KPreset.FAST -> Anime4KShaderAssets.efficiencyPreset
        Anime4KPreset.QUALITY -> Anime4KShaderAssets.qualityPreset
    }

    private fun loadShaderPasses(fileName: String): List<Anime4KHookPass> = parsedShaderFiles.getOrPut(fileName) {
        context.assets.open("shaders/$fileName").bufferedReader().use { reader ->
            Anime4KHookShaderParser.parse(reader.readText())
        }
    }

    private fun prepareAnimePrograms() {
        if (config.algorithm != VideoEnhancementAlgorithm.ANIME4K || externalCopyProgram == 0) return
        shaderFilesFor(config.anime4KPreset).forEach { fileName ->
            loadShaderPasses(fileName).forEachIndexed { index, pass ->
                val bindingNames = linkedSetOf("HOOKED", "MAIN").apply { addAll(pass.bindings) }
                check(bindingNames.size <= maxTextureUnits) {
                    "Anime4K pass ${pass.description} needs ${bindingNames.size} texture units, " +
                        "device supports $maxTextureUnits"
                }
                animePrograms.getOrPut("$fileName:$index") {
                    createProgram(VERTEX_SHADER, buildAnimeFragmentShader(pass, bindingNames), pass.description)
                }
            }
        }
    }

    private fun buildAnimeFragmentShader(pass: Anime4KHookPass, bindingNames: Set<String>): String {
        val declarations = bindingNames.joinToString("\n") { name ->
            """
			uniform sampler2D uTexture_$name;
			uniform vec2 uSize_$name;
			#define ${name}_pos uv
			#define ${name}_size uSize_$name
			#define ${name}_pt (1.0 / uSize_$name)
			#define ${name}_tex(pos) texture(uTexture_$name, (pos))
			#define ${name}_texOff(off) texture(uTexture_$name, uv + (off) / uSize_$name)
            """.trimIndent()
        }
        return """
			#version 300 es
			precision highp float;
			in vec2 uv;
			out vec4 color;
			$declarations
			${pass.source}
			void main() { color = hook(); }
        """.trimIndent()
    }

    private fun obtainNativeTarget(size: ShaderTextureSize): RenderTarget {
        val current = nativeTarget
        if (current != null && current.matches(size.width, size.height, TargetFormat.RGBA8)) return current
        current?.release()
        return createRenderTarget(size.width, size.height, TargetFormat.RGBA8).also { nativeTarget = it }
    }

    private fun obtainFsrTarget(width: Int, height: Int): RenderTarget {
        val current = fsrTarget
        if (current != null && current.width == width && current.height == height) return current
        current?.release()
        return createRenderTarget(width, height, TargetFormat.RGBA8).also { fsrTarget = it }
    }

    private fun obtainAnimeTarget(
        width: Int,
        height: Int,
        components: Int,
        protectedTextureIds: Set<Int>,
    ): RenderTarget {
        val format = if (components == 1) TargetFormat.R16F else TargetFormat.RGBA16F
        animeTargets.firstOrNull {
            it.matches(width, height, format) && it.textureId !in protectedTextureIds
        }?.let { return it }
        return createRenderTarget(width, height, format).also(animeTargets::add)
    }

    private fun createRenderTarget(width: Int, height: Int, format: TargetFormat): RenderTarget {
        val texture = IntArray(1).also { GLES30.glGenTextures(1, it, 0) }[0]
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texture)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexImage2D(
            GLES30.GL_TEXTURE_2D,
            0,
            format.internalFormat,
            width,
            height,
            0,
            format.pixelFormat,
            format.pixelType,
            null,
        )
        val framebuffer = IntArray(1).also { GLES30.glGenFramebuffers(1, it, 0) }[0]
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, framebuffer)
        GLES30.glFramebufferTexture2D(
            GLES30.GL_FRAMEBUFFER,
            GLES30.GL_COLOR_ATTACHMENT0,
            GLES30.GL_TEXTURE_2D,
            texture,
            0,
        )
        check(GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER) == GLES30.GL_FRAMEBUFFER_COMPLETE) {
            "Incomplete enhancement framebuffer ${width}x$height format=$format"
        }
        return RenderTarget(texture, framebuffer, width, height, format)
    }

    private fun createProgram(vertex: String, fragment: String, label: String): Int {
        fun compile(type: Int, source: String): Int = GLES30.glCreateShader(type).also { id ->
            GLES30.glShaderSource(id, source)
            GLES30.glCompileShader(id)
            val status = IntArray(1)
            GLES30.glGetShaderiv(id, GLES30.GL_COMPILE_STATUS, status, 0)
            check(status[0] != 0) { "$label shader compile failed: ${GLES30.glGetShaderInfoLog(id)}" }
        }
        val vertexId = compile(GLES30.GL_VERTEX_SHADER, vertex)
        val fragmentId = compile(GLES30.GL_FRAGMENT_SHADER, fragment)
        return GLES30.glCreateProgram().also { id ->
            GLES30.glAttachShader(id, vertexId)
            GLES30.glAttachShader(id, fragmentId)
            GLES30.glLinkProgram(id)
            val status = IntArray(1)
            GLES30.glGetProgramiv(id, GLES30.GL_LINK_STATUS, status, 0)
            check(status[0] != 0) { "$label program link failed: ${GLES30.glGetProgramInfoLog(id)}" }
            GLES30.glDeleteShader(vertexId)
            GLES30.glDeleteShader(fragmentId)
        }
    }

    private fun uniform(program: Int, name: String): Int = GLES30.glGetUniformLocation(program, name)

    private fun checkGlError(stage: String) {
        val error = GLES30.glGetError()
        check(error == GLES30.GL_NO_ERROR) { "OpenGL error 0x${error.toString(16)} after $stage" }
    }

    fun release() {
        releaseGlResources()
    }

    private fun releaseGlResources() {
        releaseDecoderSurface()
        releaseRenderTargets()
        animePrograms.values.forEach(GLES30::glDeleteProgram)
        animePrograms.clear()
        listOf(externalCopyProgram, displayProgram, fsrEasuProgram, fsrRcasProgram)
            .filter { it != 0 }
            .forEach(GLES30::glDeleteProgram)
        if (textureId != 0) GLES30.glDeleteTextures(1, intArrayOf(textureId), 0)
        textureId = 0
        externalCopyProgram = 0
        displayProgram = 0
        fsrEasuProgram = 0
        fsrRcasProgram = 0
    }

    private fun releaseRenderTargets() {
        nativeTarget?.release()
        nativeTarget = null
        fsrTarget?.release()
        fsrTarget = null
        animeTargets.forEach(RenderTarget::release)
        animeTargets.clear()
    }

    private data class RenderTarget(
        val textureId: Int,
        val framebufferId: Int,
        val width: Int,
        val height: Int,
        val format: TargetFormat,
    ) {
        fun matches(width: Int, height: Int, format: TargetFormat): Boolean =
            this.width == width && this.height == height && this.format == format

        fun release() {
            GLES30.glDeleteFramebuffers(1, intArrayOf(framebufferId), 0)
            GLES30.glDeleteTextures(1, intArrayOf(textureId), 0)
        }
    }

    private enum class TargetFormat(
        val internalFormat: Int,
        val pixelFormat: Int,
        val pixelType: Int,
    ) {
        RGBA8(GLES30.GL_RGBA8, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE),
        R16F(GLES30.GL_R16F, GLES30.GL_RED, GLES30.GL_HALF_FLOAT),
        RGBA16F(GLES30.GL_RGBA16F, GLES30.GL_RGBA, GLES30.GL_HALF_FLOAT),
    }

    private companion object {
        const val TAG = "VideoEnhancementGL"
        val VERTEX_SHADER = """
			#version 300 es
			layout(location=0) in vec2 position;
			layout(location=1) in vec2 texCoord;
			out vec2 uv;
			void main() { gl_Position = vec4(position, 0.0, 1.0); uv = texCoord; }
        """.trimIndent()
        val EXTERNAL_COPY_SHADER = """
			#version 300 es
			#extension GL_OES_EGL_image_external_essl3 : require
			precision highp float;
			uniform samplerExternalOES uTexture;
			uniform mat4 uTexMatrix;
			in vec2 uv;
			out vec4 color;
			void main() { color = texture(uTexture, (uTexMatrix * vec4(uv, 0.0, 1.0)).xy); }
        """.trimIndent()
        val DISPLAY_SHADER = """
			#version 300 es
			precision highp float;
			uniform sampler2D uTexture;
			in vec2 uv;
			out vec4 color;
			void main() { color = texture(uTexture, uv); }
        """.trimIndent()
        val FSR_EASU_SHADER = """
			#version 300 es
			#extension GL_OES_EGL_image_external_essl3 : require
			precision highp float;
			uniform samplerExternalOES uTexture;
			uniform mat4 uTexMatrix;
			uniform vec2 uInputSize;
			in vec2 uv;
			out vec4 color;
			vec3 tap(vec2 pixel) {
				vec2 coord = (pixel + 0.5) / uInputSize;
				return texture(uTexture, (uTexMatrix * vec4(coord, 0.0, 1.0)).xy).rgb;
			}
			float luma(vec3 value) { return value.g + 0.5 * (value.r + value.b); }
			void accumulateDirection(inout vec2 dir, inout float edge, vec2 weight, float a, float b, float c, float d, float e) {
				float w = weight.x * weight.y;
				float dx0 = d - c;
				float dx1 = c - b;
				float dy0 = e - c;
				float dy1 = c - a;
				dir += vec2(d - b, e - a) * w;
				edge += (pow(clamp(abs(d - b) / max(max(abs(dx0), abs(dx1)), 1e-5), 0.0, 1.0), 2.0) +
					pow(clamp(abs(e - a) / max(max(abs(dy0), abs(dy1)), 1e-5), 0.0, 1.0), 2.0)) * w;
			}
			void addTap(inout vec3 sum, inout float weightSum, vec2 offset, vec2 direction, vec2 length, float lobe, float clipPoint, vec3 sampleColor) {
				vec2 rotated = vec2(dot(offset, direction), dot(offset, vec2(-direction.y, direction.x))) * length;
				float distance2 = min(dot(rotated, rotated), clipPoint);
				float base = 0.4 * distance2 - 1.0;
				float window = lobe * distance2 - 1.0;
				float weight = (1.5625 * base * base - 0.5625) * window * window;
				sum += sampleColor * weight;
				weightSum += weight;
			}
			void main() {
				vec2 position = uv * uInputSize - 0.5;
				vec2 base = floor(position);
				vec2 fraction = position - base;
				vec3 b=tap(base+vec2(0,-1)), c=tap(base+vec2(1,-1));
				vec3 e=tap(base+vec2(-1,0)), f=tap(base), g=tap(base+vec2(1,0)), h=tap(base+vec2(2,0));
				vec3 i=tap(base+vec2(-1,1)), j=tap(base+vec2(0,1)), k=tap(base+vec2(1,1)), l=tap(base+vec2(2,1));
				vec3 n=tap(base+vec2(0,2)), o=tap(base+vec2(1,2));
				vec2 direction=vec2(0.0); float edge=0.0;
				accumulateDirection(direction,edge,vec2(1.0-fraction.x,1.0-fraction.y),luma(b),luma(e),luma(f),luma(g),luma(j));
				accumulateDirection(direction,edge,vec2(fraction.x,1.0-fraction.y),luma(c),luma(f),luma(g),luma(h),luma(k));
				accumulateDirection(direction,edge,vec2(1.0-fraction.x,fraction.y),luma(f),luma(i),luma(j),luma(k),luma(n));
				accumulateDirection(direction,edge,vec2(fraction.x,fraction.y),luma(g),luma(j),luma(k),luma(l),luma(o));
				float magnitude=dot(direction,direction);
				direction=magnitude < 0.0000305 ? vec2(1.0,0.0) : normalize(direction);
				edge=pow(clamp(edge*0.5,0.0,1.0),2.0);
				float stretch=(dot(direction,direction))/max(max(abs(direction.x),abs(direction.y)),1e-5);
				vec2 length=vec2(mix(1.0,stretch,edge),mix(1.0,0.5,edge));
				float lobe=mix(0.5,0.21,edge), clipPoint=1.0/lobe;
				vec3 min4=min(min(f,g),min(j,k)), max4=max(max(f,g),max(j,k));
				vec3 sum=vec3(0.0); float sumWeight=0.0;
				addTap(sum,sumWeight,vec2(0,-1)-fraction,direction,length,lobe,clipPoint,b);
				addTap(sum,sumWeight,vec2(1,-1)-fraction,direction,length,lobe,clipPoint,c);
				addTap(sum,sumWeight,vec2(-1,0)-fraction,direction,length,lobe,clipPoint,e);
				addTap(sum,sumWeight,vec2(0,0)-fraction,direction,length,lobe,clipPoint,f);
				addTap(sum,sumWeight,vec2(1,0)-fraction,direction,length,lobe,clipPoint,g);
				addTap(sum,sumWeight,vec2(2,0)-fraction,direction,length,lobe,clipPoint,h);
				addTap(sum,sumWeight,vec2(-1,1)-fraction,direction,length,lobe,clipPoint,i);
				addTap(sum,sumWeight,vec2(0,1)-fraction,direction,length,lobe,clipPoint,j);
				addTap(sum,sumWeight,vec2(1,1)-fraction,direction,length,lobe,clipPoint,k);
				addTap(sum,sumWeight,vec2(2,1)-fraction,direction,length,lobe,clipPoint,l);
				addTap(sum,sumWeight,vec2(0,2)-fraction,direction,length,lobe,clipPoint,n);
				addTap(sum,sumWeight,vec2(1,2)-fraction,direction,length,lobe,clipPoint,o);
				color=vec4(clamp(sum/max(sumWeight,1e-5),min4,max4),1.0);
			}
        """.trimIndent()
        val FSR_RCAS_SHADER = """
			#version 300 es
			precision highp float;
			uniform sampler2D uTexture;
			uniform vec2 uTextureSize;
			uniform float uSharpnessStops;
			in vec2 uv;
			out vec4 color;
			void main() {
				vec2 stepSize=1.0/uTextureSize;
				vec3 b=texture(uTexture,uv-vec2(0,stepSize.y)).rgb;
				vec3 d=texture(uTexture,uv-vec2(stepSize.x,0)).rgb;
				vec3 e=texture(uTexture,uv).rgb;
				vec3 f=texture(uTexture,uv+vec2(stepSize.x,0)).rgb;
				vec3 h=texture(uTexture,uv+vec2(0,stepSize.y)).rgb;
				vec3 minimum=min(min(b,d),min(f,h));
				vec3 maximum=max(max(b,d),max(f,h));
				vec3 hitMin=min(minimum,e)/max(4.0*maximum,vec3(1e-5));
				vec3 hitMax=(vec3(1.0)-max(maximum,e))/min(4.0*minimum-vec3(4.0),vec3(-1e-5));
				vec3 lobes=max(-hitMin,hitMax);
				float lobe=max(-0.1875,min(max(lobes.r,max(lobes.g,lobes.b)),0.0))*exp2(-clamp(uSharpnessStops,0.0,2.0));
				color=vec4(clamp((lobe*(b+d+f+h)+e)/(4.0*lobe+1.0),0.0,1.0),1.0);
			}
        """.trimIndent()
    }
}
