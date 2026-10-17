package org.skepsun.kototoro.ios

import kotlinx.serialization.json.*
import org.skepsun.kototoro.core.db.entity.ExternalExtensionRepoEntity
import org.skepsun.kototoro.extensions.repo.*

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned

data class IosRepository(val kind: String, val url: String, val title: String, val fingerprint: String)
class IosExtension internal constructor(internal val value: ExtensionStoreIndex.Extension) {
    val id: String get() = value.packageName
    val title: String get() = value.name
    val version: String get() = value.versionName
    val versionCode: Long get() = value.versionCode
    val url: String get() = value.resources.jarUrl.ifBlank { value.resources.apkUrl }
    val languages: List<String> get() = value.sources.map { it.language }.distinct()
    val isAdult: Boolean get() = value.contentWarning == ExtensionStoreIndex.ContentWarning.NSFW
    val sourceNames: List<String> get() = value.sources.map { it.name }
    /** Keep 64-bit identities as strings across the Swift bridge. Legacy name-only indexes provide no match. */
    fun sourceKeys(kind: String): List<String> = if (kind in setOf("MIHON", "ANIYOMI", "TSUNDOKU")) {
        value.sources.filter { it.id != 0L }.map { "${kind}_${it.id}" }
    } else emptyList()
    val iconUrl: String get() = value.resources.iconUrl
}
class IosRepositoryCatalog internal constructor(
    val url: String, val kind: String, val title: String, val signingKey: String,
    val externalList: String?, val website: String, internal val rows: List<ExtensionStoreIndex.Extension>,
    val resourceBaseUrl: String = url,
) {
    val extensions: List<IosExtension> get() = rows.map(::IosExtension)

    /** Absolute address of an `apk/…` or `icon/…` entry, resolved like Android (also for catalogs saved with a list-file base). */
    fun resolve(path: String): String = ExtensionRepoUrls.resolveResource(resourceBaseUrl, path)
}

/** Shared decoders and existing repository table; Swift supplies URL loading and SHA-256. */
class IosRepositories(private val library: IosLibrary) {
    /** Repositories saved before names were derived like Android's show "owner/repo" instead of the folder name. */
    @Throws(Exception::class)
    suspend fun saved(): List<IosRepository> {
        val all = library.database.getExternalExtensionRepoDao().getAll()
            .filter { it.type.name in supported }
        val seen = mutableSetOf<Pair<String, String>>()
        val result = mutableListOf<IosRepository>()
        for (entity in all) {
            val norm = normalizeRepoUrl(entity.baseUrl)
            if (seen.add(entity.type.name to norm)) {
                val title = if (entity.name.isBlank() || entity.name == "repo") repositoryName(entity.baseUrl) else entity.name
                result.add(IosRepository(entity.type.name, entity.baseUrl, title, entity.signingKeyFingerprint))
            }
        }
        return result
    }

    @Throws(Exception::class)
    fun decode(bytes: ByteArray, protobuf: Boolean, url: String, kind: String): IosRepositoryCatalog {
        require(kind in supported)
        val payload = gunzipExtensionStoreBytes(bytes)
        val legacy = !protobuf && payload.decodeToString().trimStart().startsWith("[")
        val index = if (legacy) decodeLegacyExtensionIndex(payload, repositoryName(url))
            else decodeExtensionStoreIndex(payload, protobuf)
        require(index.name.isNotBlank())
        val norm = normalizeRepoUrl(url)
        return IosRepositoryCatalog(norm, kind, index.name, index.signingKey, index.extensionListUrl,
            index.contact.website, index.extensionList?.extensions.orEmpty(),
            ExtensionRepoUrls.resourceBase(norm))
    }

    @Throws(Exception::class)
    fun external(catalog: IosRepositoryCatalog, bytes: ByteArray, protobuf: Boolean, url: String): IosRepositoryCatalog {
        val payload = gunzipExtensionStoreBytes(bytes)
        val legacy = !protobuf && payload.decodeToString().trimStart().startsWith("[")
        var signingKey = catalog.signingKey
        val rows = if (legacy) {
            decodeLegacyExtensionIndex(payload, catalog.title).extensionList?.extensions.orEmpty()
        } else {
            val fromIndex = if (protobuf) {
                runCatching { decodeExtensionStoreIndex(payload, true) }.getOrNull()
                    ?.takeIf { !it.extensionList?.extensions.isNullOrEmpty() }
            } else null
            if (signingKey.isBlank() && fromIndex != null) {
                signingKey = fromIndex.signingKey
            }
            fromIndex?.extensionList?.extensions ?: decodeExtensionStoreList(payload, protobuf).extensions
        }
        val norm = normalizeRepoUrl(catalog.url)
        // As on Android, apk/ and icon/ resolve against the repository base, never against the list file.
        return IosRepositoryCatalog(norm, catalog.kind, catalog.title, signingKey, null, catalog.website,
            rows, ExtensionRepoUrls.resourceBase(norm))
    }

    @Throws(Exception::class)
    fun release(json: String, url: String): IosRepositoryCatalog {
        val root = Json.parseToJsonElement(json).jsonObject
        val code = root.getValue("id").jsonPrimitive.long
        val version = root.getValue("tag_name").jsonPrimitive.content
        val rows = root.getValue("assets").jsonArray.mapNotNull {
            val asset = it.jsonObject
            val name = asset.getValue("name").jsonPrimitive.content
            if (!name.endsWith(".jar", true)) return@mapNotNull null
            val id = "github." + url.removePrefix("https://github.com/").replace('/', '.') + "." + name.removeSuffix(".jar")
            ExtensionStoreIndex.Extension(name, id, ExtensionStoreIndex.Resources(
                asset.getValue("browser_download_url").jsonPrimitive.content, ""), "parser", code, version,
                ExtensionStoreIndex.ContentWarning.UNSPECIFIED, emptyList())
        }
        require(rows.isNotEmpty()) { "此 GitHub 发布没有 JAR 插件" }
        val norm = normalizeRepoUrl(url)
        return IosRepositoryCatalog(norm, "JAR", root["name"]?.jsonPrimitive?.contentOrNull?.ifBlank { null } ?: version,
            "", null, norm, rows, ExtensionRepoUrls.resourceBase(norm))
    }

    @Throws(Exception::class)
    suspend fun save(catalog: IosRepositoryCatalog, fingerprint: String) {
        val normalizedUrl = normalizeRepoUrl(catalog.url)
        require(fingerprint.matches(Regex("[a-f0-9]{64}")))
        require(catalog.extensions.size <= 20000 && catalog.extensions.map { it.id }.distinct().size == catalog.extensions.size)
        val type = ExternalExtensionType.valueOf(catalog.kind)
        val dao = library.database.getExternalExtensionRepoDao()
        val previous = dao.get(type, normalizedUrl) ?: dao.get(type, catalog.url)
        val isLegacyPseudoFingerprint = previous != null && (
            previous.signingKeyFingerprint.length != 64 ||
            previous.signingKeyFingerprint == normalizedUrl.hashCode().toString(16) ||
            previous.signingKeyFingerprint == catalog.url.hashCode().toString(16) ||
            type == ExternalExtensionType.JAR
        )
        // Legacy arrays have no signing key; Swift stores SHA-256(URL) until repo.json supplies one.
        val isUrlIdentityUpgrade = previous != null && catalog.signingKey.isNotBlank() &&
            previous.signingKeyFingerprint.equals(sha256Hex(normalizedUrl), ignoreCase = true)
        val matchesPrevious = previous == null ||
            isLegacyPseudoFingerprint ||
            isUrlIdentityUpgrade ||
            previous.signingKeyFingerprint.equals(fingerprint, ignoreCase = true) ||
            (catalog.signingKey.isNotBlank() && previous.signingKeyFingerprint.equals(catalog.signingKey.trim(), ignoreCase = true)) ||
            (catalog.signingKey.isNotBlank() && previous.signingKeyFingerprint.equals(sha256Hex(catalog.signingKey.trim()), ignoreCase = true))
        require(matchesPrevious) {
            "仓库签名信息已变化，请核对地址"
        }
        val duplicate = dao.getByFingerprint(type, fingerprint)
        require(duplicate == null || duplicate.baseUrl == normalizedUrl || duplicate.baseUrl == catalog.url) { "此仓库已从其他地址添加" }
        val time = kotlin.time.Clock.System.now().toEpochMilliseconds()
        if (previous != null && previous.baseUrl != normalizedUrl) {
            dao.delete(type, previous.baseUrl)
        }
        dao.upsert(ExternalExtensionRepoEntity(type, normalizedUrl, catalog.title, null, catalog.website, fingerprint,
            previous?.createdAt ?: time, time, time, null, null))
    }

    @Throws(Exception::class)
    suspend fun remove(repository: IosRepository) {
        val dao = library.database.getExternalExtensionRepoDao()
        val type = ExternalExtensionType.valueOf(repository.kind)
        dao.delete(type, repository.url)
        val normalized = normalizeRepoUrl(repository.url)
        if (normalized != repository.url) {
            dao.delete(type, normalized)
        }
    }

    internal companion object {
        private val supported = setOf("MIHON", "ANIYOMI", "TSUNDOKU", "JAR")

        internal fun normalizeRepoUrl(url: String): String = ExtensionRepoUrls.repositoryBase(url)

        /**
         * Android's `deriveRepoName`: GitHub-hosted indexes (raw.githubusercontent.com / github.com) are named
         * "owner/repo", since their index usually sits in a branch or folder called `repo`; otherwise the index's
         * folder, then the host.
         */
        internal fun repositoryName(url: String): String {
            val host = url.substringAfter("://", "").substringBefore('/').substringBefore('?').lowercase()
            val segments = url.substringAfter("://", "").substringAfter('/', "").substringBefore('?').substringBefore('#')
                .split('/').filter(String::isNotEmpty)
            return when {
                segments.size >= 2 && (host.endsWith("githubusercontent.com") || host == "github.com") ->
                    "${segments[0]}/${segments[1]}"
                segments.size >= 2 -> segments[segments.size - 2]
                segments.isNotEmpty() && !segments.last().contains('.') -> segments.last()
                else -> host
            }
        }

        @OptIn(ExperimentalForeignApi::class)
        internal fun sha256Hex(text: String): String {
            if (text.isEmpty()) return ""
            val bytes = text.encodeToByteArray()
            val digest = UByteArray(32)
            bytes.usePinned { pinnedBytes ->
                digest.usePinned { pinnedDigest ->
                    platform.CoreCrypto.CC_SHA256(
                        pinnedBytes.addressOf(0),
                        bytes.size.toUInt(),
                        pinnedDigest.addressOf(0)
                    )
                }
            }
            return digest.joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }
        }
    }
}
