package org.skepsun.kototoro.core.parser.tvbox

import org.json.JSONObject
import org.skepsun.kototoro.parsers.model.Content

internal object TVBoxActionMetadata {

	private const val TYPE_KEY = "type"
	private const val TYPE_ACTION = "action"
	private const val ACTION_KEY = "action"

	fun encode(action: String): String = JSONObject()
		.put(TYPE_KEY, TYPE_ACTION)
		.put(ACTION_KEY, action)
		.toString()

	fun decode(content: Content): String? = decode(content.sourceData)

	fun decode(sourceData: String?): String? {
		val metadata = sourceData?.takeIf { it.isNotBlank() } ?: return null
		return runCatching {
			JSONObject(metadata)
				.takeIf { it.optString(TYPE_KEY) == TYPE_ACTION }
				?.optString(ACTION_KEY)
				?.takeIf { it.isNotBlank() }
		}.getOrNull()
	}
}
