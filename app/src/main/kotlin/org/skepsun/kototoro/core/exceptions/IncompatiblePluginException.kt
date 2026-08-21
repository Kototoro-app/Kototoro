package org.skepsun.kototoro.core.exceptions

class IncompatiblePluginException(
    val name: String?,
    cause: Throwable?,
) : RuntimeException(cause)
