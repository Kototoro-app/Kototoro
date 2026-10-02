package org.koitharu.kotatsu.parsers.util

internal fun <T : Any> T?.assertNotNull(name: String): T? {
	assert(this != null) {
		"Value $name is null"
	}
	return this
}
