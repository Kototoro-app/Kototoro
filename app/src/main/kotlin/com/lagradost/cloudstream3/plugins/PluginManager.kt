package com.lagradost.cloudstream3.plugins

import android.app.Activity
import android.content.Context
import java.io.File

/** Cloudstream's record of an installed plugin; same constructor and properties as the official app. */
data class PluginData(
	val internalName: String,
	val url: String?,
	val isOnline: Boolean,
	val filePath: String,
	val version: Int,
)

/**
 * The part of Cloudstream's plugin manager plugins call. StreamPlay and StremioX look through the installed plugins
 * to unload ones that duplicate the catalogs they register; installer plugins (AutoExtensions, Ultima) download and
 * load others. Kototoro manages plugins itself and does not let one plugin install, load or unload another, so the
 * lists are empty and those calls report failure; duplicate names are already folded when sources are published.
 */
@Suppress("UNUSED_PARAMETER", "RedundantSuspendModifier")
object PluginManager {
	val plugins: Map<String, BasePlugin> get() = emptyMap()

	fun getPluginsOnline(): Array<PluginData> = emptyArray()

	fun getPluginsLocal(): Array<PluginData> = emptyArray()

	fun unloadPlugin(absolutePath: String) = Unit

	fun getPluginPath(context: Context, internalName: String, repositoryUrl: String): File =
		File(File(File(context.filesDir, "cloudstream"), "foreign-plugins"), "$internalName.cs3")

	suspend fun downloadPlugin(
		activity: Activity,
		pluginUrl: String,
		pluginName: String,
		internalName: String,
		repositoryUrl: String,
		loadPlugin: Boolean,
	): Boolean = false

	suspend fun downloadPlugin(
		activity: Activity,
		pluginUrl: String,
		pluginName: String,
		internalName: String,
		file: File,
		loadPlugin: Boolean,
	): Boolean = false

	suspend fun loadSinglePlugin(context: Context, apiName: String): Boolean = false

	suspend fun deletePlugin(file: File): Boolean = false
}
