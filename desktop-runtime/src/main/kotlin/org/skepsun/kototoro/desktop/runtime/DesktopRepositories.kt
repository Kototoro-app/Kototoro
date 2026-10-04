package org.skepsun.kototoro.desktop.runtime

import kotlinx.coroutines.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import org.skepsun.kototoro.core.source.*
import org.skepsun.kototoro.extensions.repo.*
import org.skepsun.kototoro.dex.ApkExtensionConverter
import org.skepsun.kototoro.dex.DexConversionException
import org.skepsun.kototoro.dex.DexConversionReport
import org.skepsun.kototoro.dex.DexJarConverter
import org.skepsun.kototoro.parserhost.ParserPluginException
import org.skepsun.kototoro.parserhost.ParserPluginFailure
import org.skepsun.kototoro.parserhost.ParserPluginInspector
import org.skepsun.kototoro.source.host.MihonJarInspector
import java.io.*
import java.net.URI
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

@Serializable
data class DesktopRepository(val indexUrl: String, val name: String, val signingKey: String)

data class DesktopRepositoryCatalog(val repository: DesktopRepository, val extensions: List<ExtensionStoreIndex.Extension>)
data class DesktopManagedJar(val path: Path, val identity: MihonJarIdentity)

/** Repository metadata and immutable app-owned artifacts; construction/execution remains in source-host. */
class DesktopRepositories(root: Path, private val preferences: SourcePreferences) : Closeable {
    private val http = DesktopRepositoryHttp()
    private val artifacts = root.toAbsolutePath().normalize().resolve("extensions")
    private val inspector = MihonJarInspector()
    private val parserInspector = ParserPluginInspector()
    val errors = mutableListOf<String>()
    private var repositories = try {
        val encoded = (preferences.snapshot()["repositories"] as? SourcePreferenceValue.Text)?.value
        if (encoded == null) emptyList() else SourceProtocolJson.decodeFromString<List<DesktopRepository>>(encoded).also {
            require(it.map { repository -> repository.indexUrl }.distinct().size == it.size)
            it.forEach { repository -> repositoryUri(repository.indexUrl) }
        }
    } catch (error: Exception) {
        errors += "仓库记录读取失败：${error.message}"
        emptyList()
    }

    fun saved(): List<DesktopRepository> = repositories.toList()

    suspend fun fetch(address: String): DesktopRepositoryCatalog = withContext(Dispatchers.IO) {
        val input = repositoryUri(address)
        val name = input.path.substringAfterLast('/').lowercase()
        val candidates = when {
            name.endsWith(".pb") || name.endsWith(".json") && name != "repo.json" -> listOf(input)
            else -> {
                require(input.query == null) { "带查询参数的仓库请提供完整 index.pb、index.json 或 index.min.json 地址" }
                val base = if (name == "repo.json") input.resolve(".") else {
                    URI(input.toString().trimEnd('/') + "/")
                }
                // Modern indexes first; Aniyomi and older repositories only publish the legacy array.
                listOf(base.resolve("index.pb"), base.resolve("index.json"), base.resolve("index.min.json"))
            }
        }
        var last: Exception? = null
        for (url in candidates) {
            try { return@withContext fetchIndex(url) }
            catch (error: RepositoryHttpException) {
                if (error.status != 404) throw error
                last = error
            }
        }
        throw requireNotNull(last)
    }

    private suspend fun fetchIndex(uri: URI): DesktopRepositoryCatalog {
        val (actual, bytes) = bytes(uri)
        val legacy = !uri.path.endsWith(".pb", ignoreCase = true) && bytes.decodeToString().trimStart().startsWith("[")
        val index = if (legacy) decodeLegacyExtensionIndex(bytes, legacyRepositoryName(actual))
            else decodeExtensionStoreIndex(bytes, uri.path.endsWith(".pb", ignoreCase = true))
        require(index.name.isNotBlank()) { "仓库名称为空" }
        val previous = repositories.firstOrNull { it.indexUrl == actual.toString() }
        require(previous == null || previous.signingKey == index.signingKey) { "仓库签名信息已变化，请核对地址" }
        val external = index.extensionListUrl?.takeIf(String::isNotBlank)
        val inline = index.extensionList
        val (resourceBase, rows) = if (inline != null) actual to inline.extensions else {
            val target = repositoryUri(actual.resolve(requireNotNull(external) { "仓库没有扩展列表" }).toString())
            val (listActual, data) = bytes(target)
            listActual to decodeExtensionStoreList(data, target.path.endsWith(".pb", ignoreCase = true)).extensions
        }
        require(rows.size <= 20_000 && rows.map { it.packageName }.distinct().size == rows.size) { "仓库扩展列表无效" }
        val extensions = rows.map { row ->
            require(row.packageName.matches(Regex("[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+")) && row.versionCode > 0) {
                "仓库扩展身份无效"
            }
            fun resolved(address: String) = address.takeIf(String::isNotBlank)?.let {
                repositoryUri(resourceBase.resolve(it).toString()).toString()
            }.orEmpty()
            row.copy(resources = row.resources.copy(jarUrl = resolved(row.resources.jarUrl), apkUrl = resolved(row.resources.apkUrl)))
        }
        val repository = DesktopRepository(actual.toString(), index.name, index.signingKey)
        val updated = repositories.filterNot { it.indexUrl == repository.indexUrl } + repository
        persistRepositoryPreference(preferences, "repositories", SourcePreferenceValue.Text(SourceProtocolJson.encodeToString(updated)))
        repositories = updated
        return DesktopRepositoryCatalog(repository, extensions)
    }

    /** The legacy array has no header: name it after where it lives (owner/repository on GitHub-style hosts). */
    private fun legacyRepositoryName(uri: URI): String {
        val parts = uri.path.split('/').filter(String::isNotBlank).dropLast(1)
        // raw hosts: /owner/name/branch/…; jsDelivr: /gh/owner/name@branch/…
        val tail = parts.dropWhile { it == "gh" }.take(2).map { it.substringBefore('@') }
        return (listOf(uri.host.orEmpty()) + tail).filter(String::isNotBlank).joinToString("/").ifBlank { "扩展仓库" }
    }

    private suspend fun bytes(uri: URI): Pair<URI, ByteArray> = http.read(uri, INDEX_LIMIT) { actual, input ->
        val output = ByteArrayOutputStream()
        copy(input, output)
        actual to output.toByteArray()
    }

    suspend fun download(catalog: DesktopRepositoryCatalog, extension: ExtensionStoreIndex.Extension): DesktopManagedJar {
        require(catalog.repository in repositories && extension in catalog.extensions) { "请先刷新仓库列表" }
        // Prefer the repository's own JVM jar; an APK-only entry is downloaded and converted locally.
        val address = extension.resources.jarUrl.ifBlank { extension.resources.apkUrl }
        require(address.isNotBlank()) { "此扩展没有可下载的安装包" }
        return http.read(repositoryUri(address), JAR_LIMIT) { _, input ->
            stage(input, extension)
        }
    }

    suspend fun import(path: Path): DesktopManagedJar = withContext(Dispatchers.IO) {
        Files.newInputStream(path).use { stage(it, null) }
    }

    /**
     * A kototoro / kotatsu / Tsuki plugin jar: no Android manifest, identified by its factory entry point. The jars
     * Android repositories publish hold only `classes.dex`; those are converted to class files first, and the managed
     * artifact (what is hashed, stored and restored) is always the converted JVM jar.
     */
    suspend fun importParserPlugin(path: Path): DesktopManagedParserPlugin = withContext(Dispatchers.IO) {
        Files.newInputStream(path).use { input ->
            staged(input) { temporary ->
                var conversion: DexConversionReport? = null
                val converted = Files.createTempFile(artifacts, ".converted-", ".tmp")
                try {
                    val ready = try {
                        parserInspector.inspect(temporary)
                        temporary
                    } catch (error: ParserPluginException) {
                        if (error.failure != ParserPluginFailure.DEX_ONLY) throw error
                        conversion = try { DexJarConverter.convert(temporary, converted) } catch (failure: DexConversionException) {
                            throw IOException("无法把 Android DEX 插件转换为 JVM 类文件：${failure.failure}", failure)
                        }
                        converted
                    }
                    val metadata = parserInspector.inspect(ready)
                    val target = publish(ready, metadata.sha256)
                    parserInspector.verify(parserInspector.inspect(target), metadata.sha256)
                    DesktopManagedParserPlugin(target, DesktopExtensionFiles.parserPluginId(path.fileName.toString()),
                        metadata.sha256, conversion)
                } finally { Files.deleteIfExists(converted) }
            }
        }
    }

    /**
     * A Keiyoushi-style JAR, or an Android APK that is first converted to the same shape (class files and a text
     * manifest decoded from the binary one). The managed artifact is always the JVM jar.
     */
    private suspend fun stage(input: InputStream, expected: ExtensionStoreIndex.Extension?): DesktopManagedJar =
        staged(input) { temporary ->
            val converted = Files.createTempFile(artifacts, ".converted-", ".tmp")
            try {
                val ready = if (ApkExtensionConverter.isApk(temporary)) {
                    try { ApkExtensionConverter.convert(temporary, converted) } catch (failure: DexConversionException) {
                        throw IOException("无法把 Android APK 转换为 JVM 类文件：${failure.failure}", failure)
                    }
                    converted
                } else temporary
                val metadata = inspector.inspect(ready)
                require(expected == null || metadata.packageName == expected.packageName &&
                    metadata.versionCode == expected.versionCode && metadata.extensionLib == expected.extensionLib) {
                    "下载的扩展包名、版本或扩展 API 与仓库不一致"
                }
                val identity = MihonJarIdentity(metadata.packageName, metadata.versionCode, metadata.sha256)
                val target = publish(ready, metadata.sha256)
                inspector.verify(inspector.inspect(target), identity)
                DesktopManagedJar(target, identity)
            } finally { Files.deleteIfExists(converted) }
        }

    /** Copies [input] into an app-owned temporary file within the size limit; [inspect] publishes or throws. */
    private suspend fun <T> staged(input: InputStream, inspect: (Path) -> T): T {
        Files.createDirectories(artifacts)
        require(Files.isDirectory(artifacts, LinkOption.NOFOLLOW_LINKS)) { "扩展目录必须是真实目录" }
        val temporary = Files.createTempFile(artifacts, ".download-", ".tmp")
        try {
            Files.newOutputStream(temporary).use { output -> copy(input, output, JAR_LIMIT) }
            currentCoroutineContext().ensureActive()
            return inspect(temporary)
        } finally { Files.deleteIfExists(temporary) }
    }

    /** Artifacts are immutable and named by their SHA-256, so the same bytes are stored once. */
    private fun publish(temporary: Path, sha256: String): Path {
        val target = artifacts.resolve("$sha256.jar")
        if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) Files.move(temporary, target)
        require(Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) { "扩展文件不是普通文件" }
        return target
    }

    private suspend fun copy(input: InputStream, output: OutputStream, limit: Long = INDEX_LIMIT) {
        val buffer = ByteArray(65536)
        var copied = 0L
        while (true) {
            currentCoroutineContext().ensureActive()
            val count = input.read(buffer)
            if (count < 0) break
            copied += count
            require(copied <= limit) { "仓库数据超过大小限制" }
            output.write(buffer, 0, count)
        }
    }

    /**
     * Deletes managed artifacts no install record refers to (uninstalled or superseded versions). A file still held
     * open by a retiring class loader cannot be deleted on Windows; it is left for the next cleanup.
     */
    fun cleanupArtifacts(referenced: Set<Path>): Int {
        if (!Files.isDirectory(artifacts, LinkOption.NOFOLLOW_LINKS)) return 0
        val keep = referenced.map { it.toAbsolutePath().normalize() }.toSet()
        var removed = 0
        Files.list(artifacts).use { files ->
            for (file in files.toList()) {
                val name = file.fileName.toString()
                if (!name.endsWith(".jar") || name.startsWith(".") || file.toAbsolutePath().normalize() in keep) continue
                if (runCatching { Files.deleteIfExists(file) }.getOrDefault(false)) removed++
            }
        }
        return removed
    }

    override fun close() = http.close()

    companion object {
        private const val INDEX_LIMIT = 16L * 1024 * 1024
        private const val JAR_LIMIT = 64L * 1024 * 1024
    }
}

/** The existing preference backend publishes memory even on failed writes; restore its prior snapshot on failure. */
fun persistRepositoryPreference(preferences: SourcePreferences, key: String, value: SourcePreferenceValue) {
    val previous = preferences.snapshot()[key]
    if (!preferences.edit(SourcePreferenceEdit(changes = mapOf(key to value)))) {
        preferences.edit(SourcePreferenceEdit(changes = mapOf(key to previous)))
        throw IOException("保存仓库或扩展记录失败，未提交安装")
    }
}
