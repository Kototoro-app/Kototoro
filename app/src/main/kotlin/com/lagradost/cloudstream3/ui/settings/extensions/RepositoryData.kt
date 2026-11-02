package com.lagradost.cloudstream3.ui.settings.extensions

import com.fasterxml.jackson.annotation.JsonProperty

/**
 * A Cloudstream repository as the official app stores it, which plugins read back from the data store. Same
 * constructors, properties and JSON names as the official class.
 */
data class RepositoryData(
	@JsonProperty("iconUrl") val iconUrl: String?,
	@JsonProperty("name") val name: String,
	@JsonProperty("url") val url: String,
	@JsonProperty("description") val description: String? = null,
) {
	constructor(name: String, url: String) : this(iconUrl = null, name = name, url = url, description = null)

	constructor(iconUrl: String?, name: String, url: String) : this(
		iconUrl = iconUrl,
		name = name,
		url = url,
		description = null,
	)
}
