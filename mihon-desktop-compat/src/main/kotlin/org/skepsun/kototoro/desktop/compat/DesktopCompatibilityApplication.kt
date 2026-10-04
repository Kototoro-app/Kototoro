package org.skepsun.kototoro.desktop.compat

import android.content.Context
import android.content.SharedPreferences
import android.os.Looper
import org.skepsun.kototoro.source.host.MihonPreferenceBridge
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.LinkOption.NOFOLLOW_LINKS

/** Platform-owned Application for the pinned source API. Unimplemented Android facilities fail explicitly. */
internal class DesktopCompatibilityApplication(
    private val root: Path,
    private val preferences: MihonPreferenceBridge,
) : eu.kanade.tachiyomi.App() {
    private val files = directory("files")
    private val cache = directory("cache")
    private val noBackup = directory("no-backup")

    override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
        preferences.getSharedPreferences(name) as SharedPreferences

    override fun getApplicationContext(): Context = this
    override fun getClassLoader(): ClassLoader = javaClass.classLoader
    override fun getPackageName(): String = "org.skepsun.kototoro.desktop"
    override fun getMainLooper(): Looper = requireNotNull(Looper.getMainLooper())
    override fun getDataDir(): File = root.toFile()
    override fun getFilesDir(): File = files
    override fun getCacheDir(): File = cache
    override fun getCodeCacheDir(): File = cache
    override fun getNoBackupFilesDir(): File = noBackup

    override fun getSystemService(name: String): Any =
        throw UnsupportedOperationException("Desktop Android service is unavailable: $name")

    override fun getResources(): android.content.res.Resources =
        throw UnsupportedOperationException("Desktop Android resources are unavailable")

    override fun getPackageManager(): android.content.pm.PackageManager =
        throw UnsupportedOperationException("Desktop Android package manager is unavailable")

    private fun directory(name: String): File = root.resolve(name).also {
        Files.createDirectories(it)
        require(Files.isDirectory(it, NOFOLLOW_LINKS)) { "Compatibility storage must be a real directory" }
    }.toFile()
}
