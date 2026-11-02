package org.skepsun.kototoro.desktop.runtime

/** Host capabilities belong here; screens and business rules remain shared across desktop systems. */
enum class DesktopPlatform(val displayName: String) {
    WINDOWS("Windows"),
    MACOS("macOS"),
    LINUX("Linux"),
    OTHER("Desktop");

    val supportsWindowsPackages: Boolean get() = this == WINDOWS
    /** Official ncnn-vulkan builds are pinned for all three supported desktop systems. */
    val supportsNativeUpscaling: Boolean get() = this == WINDOWS || this == LINUX || this == MACOS

    companion object {
        val current: DesktopPlatform = detect(System.getProperty("os.name", ""))

        fun detect(osName: String): DesktopPlatform = when {
            osName.startsWith("Windows", ignoreCase = true) -> WINDOWS
            osName.startsWith("Mac", ignoreCase = true) || osName.equals("Darwin", ignoreCase = true) -> MACOS
            osName.startsWith("Linux", ignoreCase = true) -> LINUX
            else -> OTHER
        }
    }
}
