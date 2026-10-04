package org.skepsun.kototoro.backups.webdav

import org.skepsun.kototoro.backups.ui.periodical.RemoteNamespace

/** Platform XML/date parsing projects only these portable resource fields. */
data class WebDavResource(val name: String, val lastModifiedMillis: Long, val size: Long)

data class WebDavBackupFile(
    val name: String,
    val lastModifiedMillis: Long,
    val size: Long,
    val dataVersion: Int?,
    val namespace: RemoteNamespace,
) {
    val writerGeneration: Int get() = namespace.writerGeneration
}

object WebDavBackupCatalog {
    fun classify(resources: List<WebDavResource>): List<WebDavBackupFile> = resources
        .filter { it.name.endsWith(".zip", ignoreCase = true) }
        .map { resource ->
            val namespace = when {
                resource.name.startsWith("kototoro-v3-work-v") -> RemoteNamespace.V3
                resource.name.startsWith("kototoro-v2-data-v") -> RemoteNamespace.V2
                else -> RemoteNamespace.V1
            }
            WebDavBackupFile(
                resource.name,
                resource.lastModifiedMillis,
                resource.size,
                dataVersion(resource.name, namespace),
                namespace,
            )
        }
        .sortedByDescending { it.lastModifiedMillis }

    fun latest(files: List<WebDavBackupFile>): WebDavBackupFile? =
        listOf(RemoteNamespace.V3, RemoteNamespace.V2, RemoteNamespace.V1).firstNotNullOfOrNull { namespace ->
            files.firstOrNull { it.namespace == namespace }
        }

    fun retired(files: List<WebDavBackupFile>, maxCount: Int, namespace: RemoteNamespace): List<WebDavBackupFile> =
        if (maxCount <= 0) emptyList() else files.filter { it.namespace == namespace }.drop(maxCount)

    fun remoteName(version: Int, namespace: RemoteNamespace, timestamp: String): String = when (namespace) {
        RemoteNamespace.V1 -> "kototoro-v$version-$timestamp.zip"
        RemoteNamespace.V2 -> "kototoro-v2-data-v$version-$timestamp.zip"
        RemoteNamespace.V3 -> "kototoro-v3-work-v$version-$timestamp.zip"
    }

    private fun dataVersion(name: String, namespace: RemoteNamespace): Int? {
        val strict = when (namespace) {
            RemoteNamespace.V1 -> Regex("^kototoro(?:-data)?-v([0-9]+)-")
            RemoteNamespace.V2 -> Regex("^kototoro-v2-data-v([0-9]+)-")
            RemoteNamespace.V3 -> Regex("^kototoro-v3-work-v([0-9]+)-")
        }
        strict.find(name)?.let { return it.groupValues[1].toIntOrNull() }
        if (namespace != RemoteNamespace.V1) return null
        return Regex("-v([0-9]+)-").find(name)?.groupValues?.get(1)?.toIntOrNull()
    }
}
