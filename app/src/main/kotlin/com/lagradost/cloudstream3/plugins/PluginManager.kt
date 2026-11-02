package com.lagradost.cloudstream3.plugins

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
 * to unload ones that duplicate the catalogs they register. Kototoro manages plugins itself and does not let one
 * plugin unload another, so the list is empty and unloading does nothing; duplicate names are already folded when
 * sources are published.
 */
object PluginManager {
	fun getPluginsOnline(): Array<PluginData> = emptyArray()

	fun getPluginsLocal(): Array<PluginData> = emptyArray()

	@Suppress("UNUSED_PARAMETER")
	fun unloadPlugin(absolutePath: String) = Unit
}
