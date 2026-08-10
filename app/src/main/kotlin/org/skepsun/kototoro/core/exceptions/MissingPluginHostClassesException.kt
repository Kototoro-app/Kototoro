package org.skepsun.kototoro.core.exceptions

class MissingPluginHostClassesException(
	val pluginName: String,
	val hostName: String,
	val missingClassNames: List<String>,
) : RuntimeException()
