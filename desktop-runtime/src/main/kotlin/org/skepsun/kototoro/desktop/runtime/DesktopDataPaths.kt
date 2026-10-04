package org.skepsun.kototoro.desktop.runtime

import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

/** Platform-owned persistent data; no database or image is stored in a temporary/build directory by default. */
class DesktopDataPaths private constructor(val root: Path) {
    val database: Path = root.resolve("kototoro.db")
    val images: Path = root.resolve("images")
    val preferences: Path = root.resolve("preferences")

    companion object {
        /** Pure selection, with injectable environment/home for tests; does not create directories. */
        fun defaultRoot(
            environment: Map<String, String> = System.getenv(),
            userHome: String = System.getProperty("user.home"),
            osName: String = System.getProperty("os.name"),
        ): Path {
            val home = Path.of(userHome)
            require(home.isAbsolute) { "User home must be absolute" }
            if (!osName.startsWith("Windows", ignoreCase = true)) return home.resolve(".kototoro")
            val configured = environment["LOCALAPPDATA"]?.takeIf(String::isNotBlank)
            val local = configured?.let(Path::of) ?: home.resolve("AppData").resolve("Local")
            require(local.isAbsolute) { "LOCALAPPDATA must be absolute" }
            return local.resolve("Kototoro").normalize()
        }

        fun create(root: Path = defaultRoot()): DesktopDataPaths {
            val absolute = root.toAbsolutePath().normalize()
            Files.createDirectories(absolute)
            require(Files.isDirectory(absolute, LinkOption.NOFOLLOW_LINKS)) {
                "Desktop data directory must be a real directory"
            }
            return DesktopDataPaths(absolute.toRealPath())
        }
    }
}
