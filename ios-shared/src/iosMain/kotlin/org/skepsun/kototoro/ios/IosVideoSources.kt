package org.skepsun.kototoro.ios

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.skepsun.kototoro.core.db.entity.JsonSourceEntity
import org.skepsun.kototoro.core.db.entity.JsonSourceType
import org.skepsun.kototoro.tvbox.TvBoxHttp
import org.skepsun.kototoro.tvbox.TvBoxImport
import kotlinx.serialization.json.Json
import org.skepsun.kototoro.tvbox.TvBoxSource

data class IosVideoSource(val id: String, val name: String, val api: String, val iconUrl: String?)

/** A CMS site's own settings: its API address (the site's "domain") and the User-Agent its requests carry. */
data class IosVideoSiteSettings(val api: String, val userAgent: String)

data class IosImportSummary(val imported: Int, val skippedSpiderSites: Int, val errors: List<String>)

/** TVBox / Apple CMS sites in the shared `json_sources` table, written in Android's storage format. */
class IosVideoSources internal constructor(private val library: IosLibrary, internal val http: TvBoxHttp) {
    constructor(library: IosLibrary) : this(library, IosHttp)

    private val dao get() = library.database.getJsonSourceDao()

    @Throws(Exception::class)
    suspend fun list(): List<IosVideoSource> = stored().map { IosVideoSource(it.id, it.name, it.stored.api, it.iconUrl) }

    /** Accepts a TVBox config / multi-repository URL or a MacCMS API URL. Existing sites keep enabled state. */
    @Throws(Exception::class)
    suspend fun importFrom(url: String, title: String?): IosImportSummary {
        val result = TvBoxImport.importFromUrl(http, url, title?.trim()?.ifEmpty { null })
        if (result.sources.isEmpty()) {
            // A readable repository full of spider sites is the common case for public TVBox lists; say so rather
            // than surfacing whichever child repository happened to fail.
            val reason = when {
                result.skippedSpiderSites > 0 ->
                    localized("该配置的 %@ 个站点都需要爬虫运行时（type 3/4），iOS 暂不支持", result.skippedSpiderSites)
                result.errors.isNotEmpty() -> result.errors.first()
                else -> localized("没有找到可用的苹果 CMS 站点")
            }
            throw IllegalArgumentException(reason)
        }
        val now = kotlin.time.Clock.System.now().toEpochMilliseconds()
        dao.insertAll(result.sources.map { source ->
            val existing = dao.getById(source.id)
            JsonSourceEntity(
                id = source.id,
                name = source.name,
                type = JsonSourceType.TVBOX,
                config = source.config,
                enabled = existing?.enabled ?: true,
                createdAt = existing?.createdAt ?: now,
                updatedAt = now,
                lastUsedAt = existing?.lastUsedAt ?: 0,
                isPinned = existing?.isPinned ?: false,
                iconUrl = source.iconUrl ?: existing?.iconUrl,
            )
        })
        return IosImportSummary(result.sources.size, result.skippedSpiderSites, result.errors)
    }

    @Throws(Exception::class)
    suspend fun delete(id: String) = dao.deleteById(id)

    @Throws(Exception::class)
    suspend fun settings(id: String): IosVideoSiteSettings {
        val stored = requireNotNull(dao.getById(id)) { localized("站点已不存在") }
        val config = TvBoxSource(stored.id, stored.name, stored.config).stored
        val userAgent = config.staticHeaders.entries.firstOrNull { it.key.equals(USER_AGENT, true) }?.value.orEmpty()
        return IosVideoSiteSettings(config.api, userAgent)
    }

    /**
     * Rewrites the stored site (Android's `site` object) with a new API address and User-Agent; a blank User-Agent
     * removes the override. Other site fields, including other headers, are kept as imported.
     */
    @Throws(Exception::class)
    suspend fun updateSettings(id: String, api: String, userAgent: String) {
        val address = api.trim()
        require(address.startsWith("http://", true) || address.startsWith("https://", true)) {
            localized("API 地址需要以 http:// 或 https:// 开头")
        }
        val agent = userAgent.trim()
        require(agent.none { it == '\r' || it == '\n' }) { localized("User-Agent 不能包含换行") }
        val stored = requireNotNull(dao.getById(id)) { localized("站点已不存在") }
        val root = SiteJson.parseToJsonElement(stored.config) as? JsonObject ?: throw IllegalStateException(localized("站点配置无效"))
        val site = root["site"] as? JsonObject ?: JsonObject(emptyMap())
        val headerKey = if (site["headers"] is JsonObject || site["header"] !is JsonObject) "headers" else "header"
        val headers = (site[headerKey] as? JsonObject).orEmpty().filterKeys { !it.equals(USER_AGENT, true) }
            .let { if (agent.isEmpty()) it else it + (USER_AGENT to JsonPrimitive(agent)) }
        val updatedSite = JsonObject(site + ("api" to JsonPrimitive(address)) + (headerKey to JsonObject(headers)))
        val config = SiteJson.encodeToString(JsonObject.serializer(), JsonObject(root + ("site" to updatedSite)))
        dao.insertAll(listOf(stored.copy(config = config,
            updatedAt = kotlin.time.Clock.System.now().toEpochMilliseconds())))
    }

    /** Enabled CMS sites; spider sites imported on Android stay in the table but are not served here. */
    private companion object {
        const val USER_AGENT = "User-Agent"
        val SiteJson = Json { ignoreUnknownKeys = true; isLenient = true }
    }

    internal suspend fun stored(): List<TvBoxSource> = dao.findAll()
        .filter { it.type == JsonSourceType.TVBOX && it.enabled }
        .map { TvBoxSource(it.id, it.name, it.config, it.iconUrl) }
        .filterNot { runCatching { it.stored.requiresSpider }.getOrDefault(true) }
}
