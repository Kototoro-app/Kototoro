package org.koitharu.kotatsu.parsers.util.json

public fun String.unescapeJson(): String {
	val builder = StringBuilder()
	var i = 0
	while (i < length) {
		val delimiter = this[i]
		i++ // consume letter or backslash
		if (delimiter == '\\' && i < length) {
			val ch = this[i]
			i++

			when (ch) {
				'\\', '/', '"', '\'' -> builder.append(ch)
				'n' -> builder.append('\n')
				'r' -> builder.append('\r')
				't' -> builder.append('\t')
				'b' -> builder.append('\b')
				'u' -> {
					val hex = StringBuilder(4)
					require(i + 4 <= length) { "Not enough unicode digits!" }
					for (x in substring(i, i + 4)) {
						require(x.isLetterOrDigit()) { "Bad character in unicode escape" }
						hex.append(x.lowercase())
					}
					i += 4 // consume those four digits.
					val code = hex.toString().toInt(16)
					builder.append(code.toChar())
				}

				else -> throw IllegalArgumentException("Illegal escape sequence: \\$ch")
			}
		} else {
			builder.append(delimiter)
		}
	}
	return builder.toString()
}
