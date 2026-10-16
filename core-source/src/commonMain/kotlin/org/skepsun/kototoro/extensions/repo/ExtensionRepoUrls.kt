package org.skepsun.kototoro.extensions.repo

/** Repository address rules shared by Android and iOS: where a repository's resources live. */
object ExtensionRepoUrls {
    private val indexFileNames = listOf("index.min.json", "index.json", "index.pb", "repo.json", "plugins.json")

    /** The repository directory: the address without its index/metadata file name or trailing slashes. */
    fun repositoryBase(url: String): String {
        var clean = url.trim()
        for (name in indexFileNames) {
            if (clean.endsWith("/$name", ignoreCase = true)) clean = clean.dropLast(name.length + 1)
        }
        while (clean.endsWith('/') && clean.length > 8) clean = clean.dropLast(1)
        return clean
    }

    /** Resources (`apk/…`, `icon/…`) resolve against the repository directory, never against the list file. */
    fun resourceBase(url: String): String = repositoryBase(url).let { if (it.endsWith('/')) it else "$it/" }

    /** Absolute addresses pass through; relative ones are appended to [base]'s directory. */
    fun resolveResource(base: String, path: String): String {
        if (path.startsWith("http://") || path.startsWith("https://")) return path
        return resourceBase(base) + path.removePrefix("/")
    }
}
