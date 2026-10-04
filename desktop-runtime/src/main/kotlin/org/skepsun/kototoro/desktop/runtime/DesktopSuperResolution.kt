package org.skepsun.kototoro.desktop.runtime

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.time.Duration
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream
import kotlin.io.path.extension

/** The official ncnn-vulkan programs the Android app's models come from, pinned by release and SHA-256. */
enum class DesktopUpscaleTool(
    val title: String,
    internal val repository: String,
    internal val tag: String,
    val asset: String,
    internal val sha256: String,
    internal val executable: String,
) {
    REALCUGAN("RealCUGAN (nihui)", "nihui/realcugan-ncnn-vulkan", "20220728",
        "realcugan-ncnn-vulkan-20220728-windows.zip",
        "c6e08d46c11704b1e3a1ada9ddd591cb5005f52f132136c8633ba25def400e01", "realcugan-ncnn-vulkan.exe"),
    REALESRGAN("Real-ESRGAN (xinntao)", "xinntao/Real-ESRGAN", "v0.2.5.0",
        "realesrgan-ncnn-vulkan-20220424-windows.zip",
        "abc02804e17982a3be33675e4d471e91ea374e65b70167abc09e31acb412802d", "realesrgan-ncnn-vulkan.exe"),
}

/**
 * The reader's image models, as on Android: RealCUGAN 2x (with its noise levels), Real-ESRGAN 4x anime (plus the
 * lighter AnimeVideo v3 2x) through the official programs, and Android's six Anime4K modes, which have no program
 * ([tool] null) and run on the [DesktopSuperResolution] shader processor. Input limits keep outputs near 50 Mpx.
 */
enum class DesktopUpscaleModel(val title: String, val tool: DesktopUpscaleTool?, val scale: Int, val maxInputPixels: Long) {
    REALCUGAN_2X("RealCUGAN 2x", DesktopUpscaleTool.REALCUGAN, 2, 3000L * 4200L),
    REALESRGAN_4X_ANIME("Real-ESRGAN 4x 动漫", DesktopUpscaleTool.REALESRGAN, 4, 1500L * 2100L),
    REALESR_ANIMEVIDEO_2X("Real-ESR AnimeVideo 2x（快速）", DesktopUpscaleTool.REALESRGAN, 2, 3000L * 4200L),
    ANIME4K_A("Anime4K A", null, 2, 3000L * 4200L),
    ANIME4K_B("Anime4K B", null, 2, 3000L * 4200L),
    ANIME4K_C("Anime4K C（仅修复）", null, 1, 6000L * 8400L),
    ANIME4K_AA("Anime4K A+A", null, 2, 3000L * 4200L),
    ANIME4K_BB("Anime4K B+B", null, 2, 3000L * 4200L),
    ANIME4K_CA("Anime4K C+A（仅修复）", null, 1, 6000L * 8400L),
}

/** Renders a shader-based model ([DesktopUpscaleModel.tool] null) from input to a PNG output; blocking. */
fun interface DesktopShaderUpscaler {
    fun upscale(input: Path, output: Path, width: Int, height: Int, model: DesktopUpscaleModel)
}

/** [noise] applies to RealCUGAN: -1 conservative, 0 none, 1–3 denoise strength. */
data class DesktopUpscaleSetting(val model: DesktopUpscaleModel? = null, val noise: Int = -1)

class DesktopUpscaleException(message: String, cause: Throwable? = null) : IOException(message, cause)

/**
 * Upscales reader pages with the official command-line programs. They are not bundled: [install] fetches the pinned
 * release archive (GitHub's asset API first, then the public download link), checks its SHA-256 and unpacks it into the
 * data directory. Results are PNG files cached by input content and setting; one image is processed at a time.
 */
class DesktopSuperResolution(
    root: Path,
    private val cacheLimitBytes: Long = 1024L * 1024 * 1024,
    /** Present when the platform can run shaders (libmpv); null makes Anime4K models report themselves unavailable. */
    private val shaderUpscaler: (() -> DesktopShaderUpscaler?)? = null,
    /** Programs shipped with the app (esources/upscale/<tool>), preferred over downloaded ones. */
    private val bundled: Path? = System.getProperty("compose.application.resources.dir")?.takeIf(String::isNotBlank)
        ?.let { Path.of(it, "upscale") },
) {
    private val tools = root.toAbsolutePath().normalize().resolve("tools")
    private val cache = root.toAbsolutePath().normalize().resolve("cache/upscale")
    private val gpu = Mutex()
    private val installation = Mutex()

    /** Whether [model] can run now: its program is installed, or the shader processor is available. */
    fun available(model: DesktopUpscaleModel): Boolean =
        model.tool?.let { executable(it) != null } ?: (shaderUpscaler?.invoke() != null)

    fun executable(tool: DesktopUpscaleTool): Path? = listOfNotNull(bundled, tools)
        .map { it.resolve(tool.name.lowercase()).resolve(tool.executable) }.firstOrNull { Files.isRegularFile(it) }

    suspend fun install(tool: DesktopUpscaleTool, progress: (Long, Long) -> Unit = { _, _ -> }): Path =
        installation.withLock {
            executable(tool)?.let { return@withLock it }
            withContext(Dispatchers.IO) {
                Files.createDirectories(tools)
                val archive = Files.createTempFile(tools, ".download-", ".zip")
                try {
                    download(tool, archive, progress)
                    unpackVerified(tool, archive)
                } finally {
                    Files.deleteIfExists(archive)
                }
            }
        }

    /** Installs from an archive the user already has (the same pinned release zip); its SHA-256 is checked. */
    suspend fun installArchive(tool: DesktopUpscaleTool, archive: Path): Path = installation.withLock {
        withContext(Dispatchers.IO) { Files.createDirectories(tools); unpackVerified(tool, archive) }
    }

    private fun unpackVerified(tool: DesktopUpscaleTool, archive: Path): Path {
                val staging = Files.createTempDirectory(tools, ".unpack-")
                try {
                    val digest = sha256(archive)
                    if (!digest.equals(tool.sha256, ignoreCase = true)) {
                        throw DesktopUpscaleException("${tool.title} 下载校验失败（SHA-256 不符）")
                    }
                    unpack(archive, staging)
                    val target = tools.resolve(tool.name.lowercase())
                    deleteTree(target)
                    Files.move(staging, target, StandardCopyOption.ATOMIC_MOVE)
                    return executable(tool) ?: throw DesktopUpscaleException("${tool.title} 压缩包中没有 ${tool.executable}")
                } finally {
                    deleteTree(staging)
                }
    }

    /**
     * The upscaled PNG of [input] (an image of [width]×[height]), or null when the setting is off or the page is
     * larger than the model's input budget. Throws when the program is missing or fails.
     */
    suspend fun upscale(input: Path, width: Int, height: Int, setting: DesktopUpscaleSetting): Path? {
        val model = setting.model ?: return null
        if (width.toLong() * height.toLong() > model.maxInputPixels) return null
        val program: Path? = model.tool?.let { executable(it) ?: throw DesktopUpscaleException("尚未安装 ${it.title}") }
        val shader = if (model.tool == null) shaderUpscaler?.invoke()
            ?: throw DesktopUpscaleException("Anime4K 需要 libmpv（与视频播放相同）") else null
        return withContext(Dispatchers.IO) {
            val key = cacheKey(input, setting)
            val output = cache.resolve("$key.png")
            if (Files.isRegularFile(output) && Files.size(output) > 0) {
                Files.setLastModifiedTime(output, java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis()))
                return@withContext output
            }
            gpu.withLock {
                if (Files.isRegularFile(output) && Files.size(output) > 0) return@withLock output
                Files.createDirectories(cache)
                val extension = imageExtension(input) ?: return@withLock null
                val source = Files.createTempFile(cache, ".in-", ".$extension")
                val result = Files.createTempFile(cache, ".out-", ".png")
                try {
                    Files.copy(input, source, StandardCopyOption.REPLACE_EXISTING)
                    if (shader != null) shader.upscale(source, result, width, height, model)
                    else run(requireNotNull(program), model, setting.noise, source, result)
                    if (Files.size(result) == 0L) throw DesktopUpscaleException("${model.title} 没有生成图片")
                    Files.move(result, output, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
                    trim()
                    output
                } finally {
                    Files.deleteIfExists(source)
                    Files.deleteIfExists(result)
                }
            }
        }
    }

    private suspend fun run(program: Path, model: DesktopUpscaleModel, noise: Int, input: Path, output: Path) {
        val directory = program.parent
        val arguments = when (model) {
            DesktopUpscaleModel.REALCUGAN_2X -> listOf("-s", "2", "-n", noise.coerceIn(-1, 3).toString(),
                "-m", directory.resolve("models-se").toString())
            DesktopUpscaleModel.REALESRGAN_4X_ANIME -> listOf("-s", "4", "-n", "realesrgan-x4plus-anime",
                "-m", directory.resolve("models").toString())
            DesktopUpscaleModel.REALESR_ANIMEVIDEO_2X -> listOf("-s", "2", "-n", "realesr-animevideov3",
                "-m", directory.resolve("models").toString())
            else -> throw DesktopUpscaleException("${model.title} 不使用外部程序")
        }
        val process = ProcessBuilder(listOf(program.toString(), "-i", input.toString(), "-o", output.toString(), "-f", "png") + arguments)
            .directory(directory.toFile()).redirectErrorStream(true).start()
        val log = StringBuilder()
        val reader = Thread({
            process.inputStream.bufferedReader().useLines { lines -> lines.forEach { synchronized(log) { log.appendLine(it) } } }
        }, "upscale-output").apply { isDaemon = true; start() }
        try {
            while (!process.waitFor(200, TimeUnit.MILLISECONDS)) currentCoroutineContext().ensureActive()
        } finally {
            if (process.isAlive) process.destroyForcibly()
        }
        reader.join(1000)
        if (process.exitValue() != 0 || !Files.isRegularFile(output) || Files.size(output) == 0L) {
            val tail = synchronized(log) { log.lines().filter(String::isNotBlank).takeLast(4).joinToString(" / ") }
            throw DesktopUpscaleException("${model.title} 处理失败（${process.exitValue()}）：$tail")
        }
    }

    private fun download(tool: DesktopUpscaleTool, target: Path, progress: (Long, Long) -> Unit) {
        val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20))
            .followRedirects(HttpClient.Redirect.NORMAL).build()
        val failures = mutableListOf<String>()
        val candidates = buildList {
            runCatching {
                val release = client.send(HttpRequest.newBuilder(
                    URI("https://api.github.com/repos/${tool.repository}/releases/tags/${tool.tag}"))
                    .timeout(Duration.ofSeconds(30)).header("Accept", "application/vnd.github+json").GET().build(),
                    HttpResponse.BodyHandlers.ofString())
                Json.parseToJsonElement(release.body()).jsonObject["assets"]!!.jsonArray
                    .map { it.jsonObject }.first { it["name"]!!.jsonPrimitive.content == tool.asset }["url"]!!.jsonPrimitive.content
            }.onSuccess { add(it to "application/octet-stream") }.onFailure { failures += "API: ${it.message}" }
            add("https://github.com/${tool.repository}/releases/download/${tool.tag}/${tool.asset}" to "*/*")
        }
        for ((url, accept) in candidates) {
            try {
                val response = client.send(HttpRequest.newBuilder(URI(url)).timeout(Duration.ofMinutes(15))
                    .header("Accept", accept).GET().build(), HttpResponse.BodyHandlers.ofInputStream())
                response.body().use { body ->
                    if (response.statusCode() !in 200..299) throw IOException("HTTP ${response.statusCode()}")
                    val total = response.headers().firstValueAsLong("Content-Length").orElse(-1)
                    Files.newOutputStream(target).use { output ->
                        val buffer = ByteArray(1 shl 16)
                        var copied = 0L
                        while (true) {
                            val count = body.read(buffer)
                            if (count < 0) break
                            output.write(buffer, 0, count)
                            copied += count
                            if (copied > 256L * 1024 * 1024) throw IOException("下载超过大小限制")
                            progress(copied, total)
                        }
                    }
                }
                return
            } catch (error: Exception) {
                failures += "${url.substringBefore('?')}: ${error.message ?: error.javaClass.simpleName}"
            }
        }
        throw DesktopUpscaleException("${tool.title} 下载失败：${failures.joinToString("；")}")
    }

    /** Unpacks without the archive's top folder; entries may not leave the staging directory. */
    private fun unpack(archive: Path, staging: Path) {
        ZipInputStream(Files.newInputStream(archive)).use { zip ->
            val entries = mutableListOf<Pair<String, ByteArray?>>()
            while (true) {
                val entry = zip.nextEntry ?: break
                entries += entry.name.replace('\\', '/') to if (entry.isDirectory) null else zip.readAllBytes()
            }
            val tops = entries.map { it.first.substringBefore('/') }.toSet()
            val strip = if (tops.size == 1 && entries.all { '/' in it.first || it.second == null }) tops.single() + "/" else ""
            for ((name, bytes) in entries) {
                val relative = name.removePrefix(strip).takeIf(String::isNotBlank) ?: continue
                val target = staging.resolve(relative).normalize()
                require(target.startsWith(staging)) { "Archive entry escapes its directory" }
                if (bytes == null) Files.createDirectories(target)
                else { Files.createDirectories(target.parent); Files.write(target, bytes) }
            }
        }
    }

    private fun cacheKey(input: Path, setting: DesktopUpscaleSetting): String {
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(input).use { stream ->
            val buffer = ByteArray(1 shl 16)
            while (true) { val count = stream.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
        }
        digest.update("${setting.model}|${setting.noise}".toByteArray())
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /** The programs decode by extension for WebP and by content otherwise; formats they cannot read are skipped. */
    private fun imageExtension(input: Path): String? {
        val head = Files.newInputStream(input).use { it.readNBytes(12) }
        fun at(offset: Int, text: String) = head.size >= offset + text.length &&
            text.indices.all { head[offset + it] == text[it].code.toByte() }
        return when {
            at(0, "RIFF") && at(8, "WEBP") -> "webp"
            head.size >= 4 && head[0] == 0x89.toByte() && at(1, "PNG") -> "png"
            head.size >= 3 && head[0] == 0xFF.toByte() && head[1] == 0xD8.toByte() -> "jpg"
            input.extension.lowercase() in setOf("png", "jpg", "jpeg", "webp") -> input.extension.lowercase()
            else -> null
        }
    }

    /** Oldest results go first once the cache exceeds its limit. */
    private fun trim() {
        val files = Files.list(cache).use { stream -> stream.filter { it.fileName.toString().endsWith(".png") &&
            !it.fileName.toString().startsWith(".") }.toList() }
        var total = files.sumOf { Files.size(it) }
        for (file in files.sortedBy { Files.getLastModifiedTime(it).toMillis() }) {
            if (total <= cacheLimitBytes) break
            total -= Files.size(file)
            Files.deleteIfExists(file)
        }
    }

    private fun sha256(path: Path): String {
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(path).use { stream ->
            val buffer = ByteArray(1 shl 16)
            while (true) { val count = stream.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun deleteTree(path: Path) {
        if (!Files.exists(path)) return
        Files.walk(path).use { stream -> stream.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) } }
    }
}
