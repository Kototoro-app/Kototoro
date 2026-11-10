package org.skepsun.kototoro.core.util

fun String.splitNotBlank(delimiter: String): List<String> =
    this.split(delimiter).mapNotNull { it.trim().takeIf { part -> part.isNotEmpty() } }
