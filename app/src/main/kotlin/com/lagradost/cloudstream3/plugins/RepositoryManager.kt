package com.lagradost.cloudstream3.plugins

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.amap
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.ui.settings.extensions.RepositoryData
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import java.util.concurrent.CopyOnWriteArrayList

/** A repository manifest (`repo.json`). */
data class Repository(
	@JsonProperty("iconUrl") val iconUrl: String?,
	@JsonProperty("name") val name: String,
	@JsonProperty("description") val description: String?,
	@JsonProperty("manifestVersion") val manifestVersion: Int,
	@JsonProperty("pluginLists") val pluginLists: List<String>,
)

/** One entry of a plugin list. Status: 0 down, 1 ok, 2 slow, 3 beta only. */
data class SitePlugin(
	@JsonProperty("url") val url: String,
	@JsonProperty("status") val status: Int,
	@JsonProperty("version") val version: Int,
	@JsonProperty("apiVersion") val apiVersion: Int,
	@JsonProperty("name") val name: String,
	@JsonProperty("internalName") val internalName: String,
	@JsonProperty("authors") val authors: List<String>,
	@JsonProperty("description") val description: String?,
	@JsonProperty("repositoryUrl") val repositoryUrl: String?,
	@JsonProperty("tvTypes") val tvTypes: List<String>?,
	@JsonProperty("language") val language: String?,
	@JsonProperty("iconUrl") val iconUrl: String?,
	@JsonProperty("fileSize") val fileSize: Long?,
	@JsonProperty("fileHash") val fileHash: String?,
)

data class PluginWrapper(
	@JsonProperty("repository") val repository: Repository,
	@JsonProperty("repositoryData") val repositoryData: RepositoryData,
	@JsonProperty("plugin") val plugin: SitePlugin,
)

/**
 * The part of Cloudstream's repository manager plugins call: browsing repositories (StreamCenter, AutoExtensions)
 * and adding them (MegaRepo adds Cloudstream's whole community list when it loads). Kototoro's repositories need the
 * person to trust each one, so a plugin's additions stay in this list, which only plugins read.
 */
object RepositoryManager {
	val PREBUILT_REPOSITORIES: Array<RepositoryData> = emptyArray()

	private val repositories = CopyOnWriteArrayList<RepositoryData>()

	fun getRepositories(): Array<RepositoryData> = repositories.toTypedArray()

	suspend fun addRepository(repository: RepositoryData) {
		repositories.addIfAbsent(repository)
	}

	/** Cloudstream rewrites raw GitHub links to a CDN only when its mirror setting is on, which it is not by default. */
	fun convertRawGitUrl(url: String): String = url

	suspend fun parseRepository(url: String): Repository? =
		runCatching { tryParseJson<Repository>(app.get(convertRawGitUrl(url)).text) }.getOrNull()

	private suspend fun parsePlugins(pluginsUrl: String): List<SitePlugin> =
		runCatching { tryParseJson<Array<SitePlugin>>(app.get(convertRawGitUrl(pluginsUrl)).text)?.toList() }
			.getOrNull()
			.orEmpty()

	/** Every plugin of a repository, with the repository it came from. */
	suspend fun getRepoPlugins(repositoryData: RepositoryData): List<PluginWrapper>? {
		val repository = parseRepository(repositoryData.url) ?: return null
		return repository.pluginLists
			.amap { url -> parsePlugins(url).map { PluginWrapper(repository, repositoryData, it) } }
			.flatten()
	}
}
