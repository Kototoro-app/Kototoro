package org.skepsun.kototoro.core.exceptions

import org.skepsun.kototoro.parsers.model.Content

class UnsupportedSourceException(
    message: String?,
    val manga: Content?,
) : IllegalArgumentException(message)
