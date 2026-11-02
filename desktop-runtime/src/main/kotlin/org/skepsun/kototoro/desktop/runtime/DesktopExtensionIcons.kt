package org.skepsun.kototoro.desktop.runtime

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

/**
 * Android shows a Mihon/Aniyomi/Tsundoku source with the icon packaged in its extension APK
 * (`FaviconFetcher.fetchPluginIcon` → `PackageManager.getApplicationIcon`), never with a network fetch.
 *
 * The desktop host converts an APK to a JVM jar and drops everything else, so the launcher bitmap is copied out of
 * the archive while it is still there and stored beside the immutable artifact (`<sha256>.icon`). The icon is picked
 * the way a launcher would: a `ic_launcher` bitmap under `res/mipmap-*`, densest first, and only real images (the
 * adaptive-icon XML of `mipmap-anydpi-v26` is not one).
 */
object DesktopExtensionIcons {
    private const val MAXIMUM_ICON_BYTES = 512 * 1024
    private val BITMAP = Regex("""^res/(mipmap|drawable)[^/]*/([^/]+)\.(png|webp|jpg|jpeg)$""", RegexOption.IGNORE_CASE)
    private val DENSITIES = listOf("xxxhdpi", "xxhdpi", "xhdpi", "hdpi", "mdpi", "ldpi", "nodpi", "anydpi")

    /** Where the extracted icon of [artifact] lives; the artifact itself is immutable and named by its hash. */
    fun pathOf(artifact: Path): Path {
        val name = artifact.fileName.toString()
        val sha = name.substringBeforeLast('.')
        return artifact.resolveSibling("$sha.icon")
    }

    /** Copies the best launcher bitmap of [apk] beside [artifact]; returns the stored path or null when it has none. */
    fun store(apk: Path, artifact: Path): Path? {
        val bytes = extract(apk) ?: return null
        val target = pathOf(artifact)
        return runCatching {
            if (!Files.isRegularFile(target)) {
                val part = target.resolveSibling("${target.fileName}.part")
                Files.write(part, bytes)
                Files.move(part, target, StandardCopyOption.REPLACE_EXISTING)
            }
            target
        }.getOrNull()
    }

    /** The launcher bitmap an APK carries, or null when it only has adaptive XML or nothing image-shaped. */
    fun extract(apk: Path): ByteArray? = runCatching {
        ZipFile(apk.toFile()).use { zip ->
            zip.entries().asSequence()
                .filterNot { it.isDirectory }
                .mapNotNull { entry -> rank(entry.name)?.let { score -> entry to score } }
                .filter { (entry, _) -> entry.size in 1..MAXIMUM_ICON_BYTES }
                .sortedWith(compareByDescending<Pair<ZipEntry, Int>> { it.second }.thenByDescending { it.first.size })
                // A name promising an icon over bytes that are not one falls through to the next candidate.
                .firstNotNullOfOrNull { (entry, _) ->
                    zip.getInputStream(entry).use { it.readNBytes(MAXIMUM_ICON_BYTES + 1) }.takeIf(::isImage)
                }
        }
    }.getOrNull()

    /** Higher is a better launcher icon: the declared `ic_launcher` of the densest `mipmap` wins over a stray drawable. */
    private fun rank(path: String): Int? {
        val match = BITMAP.find(path.replace('\\', '/')) ?: return null
        val (kind, file, extension) = match.destructured
        val base = file.lowercase()
        // A nine-patch is a scalable frame, not a square icon.
        if (base.endsWith(".9")) return null
        val name = when {
            base.startsWith("ic_launcher") -> 3
            "launcher" in base || "app_icon" in base -> 2
            "icon" in base || "logo" in base -> 1
            else -> return null
        }
        val folder = path.substringBefore('/' + file).substringAfter("res/").lowercase()
        val density = DENSITIES.indexOfFirst { it in folder }.let { if (it < 0) 0 else DENSITIES.size - it }
        val quality = if (extension.equals("png", true)) 2 else 1
        return name * 100 + density * 10 + quality + if (kind.equals("mipmap", true)) 1 else 0
    }

    private fun isImage(bytes: ByteArray): Boolean = when {
        bytes.size > 8 && bytes[0] == 0x89.toByte() && bytes[1] == 'P'.code.toByte() -> true
        bytes.size > 12 && bytes[0] == 'R'.code.toByte() && bytes[8] == 'W'.code.toByte() -> true
        bytes.size > 3 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() -> true
        else -> false
    }
}
