package org.skepsun.kototoro.source.host

import kotlinx.serialization.Serializable
import org.skepsun.kototoro.core.source.MihonJarIdentity

/** Local desktop launch configuration. Relative paths resolve against the configuration's directory. */
@Serializable
data class SourceHostConfig(
    val platformClass: String,
    val jars: List<SourceHostJar> = emptyList(),
    val imageDirectory: String? = null,
    val preferenceDirectory: String? = null,
)

@Serializable
data class SourceHostJar(val path: String, val identity: MihonJarIdentity)
