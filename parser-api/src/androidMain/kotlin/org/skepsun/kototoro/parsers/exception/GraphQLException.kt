package org.skepsun.kototoro.parsers.exception

import okio.IOException
import org.json.JSONArray
import org.skepsun.kototoro.parsers.InternalParsersApi
import org.skepsun.kototoro.parsers.util.json.mapJSONNotNull

public class GraphQLException @InternalParsersApi constructor(errors: JSONArray) : IOException() {

	public val messages: List<String> = errors.mapJSONNotNull {
		it.getString("message")
	}

	override val message: String
		get() = messages.joinToString("\n")
}
