package org.skepsun.kototoro.tsundoku

import android.content.Context
import org.skepsun.kototoro.extensions.runtime.LocalApkExtensionSupport
import org.skepsun.kototoro.mihon.util.ChildFirstPathClassLoader
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Android/os-specific APK staging + ClassLoader construction for Tsundoku extensions, injected
 * into [TsundokuExtensionLoader] so the load path stays unit-testable off-device
 * (plan §6.1: scanning / ClassLoader construction are injected).
 */
interface TsundokuApkAccessors {
    /** Stage the extension APK into a loadable path (returns it unchanged when it is absent). */
    fun prepareApkPath(context: Context, ecosystem: String, pkgName: String, sourcePath: String): String

    /** Build the child-first classloader over a staged APK. */
    fun createClassLoader(dexPath: String, nativeLibDir: String?, parent: ClassLoader): ClassLoader
}

/** Production implementation: private cache dir + [ChildFirstPathClassLoader]. */
@Singleton
class DefaultTsundokuApkAccessors @Inject constructor() : TsundokuApkAccessors {

    override fun prepareApkPath(context: Context, ecosystem: String, pkgName: String, sourcePath: String): String {
        return LocalApkExtensionSupport.prepareLoadableApkPath(context, ecosystem, pkgName, sourcePath)
    }

    override fun createClassLoader(dexPath: String, nativeLibDir: String?, parent: ClassLoader): ClassLoader {
        return ChildFirstPathClassLoader(dexPath, nativeLibDir, parent)
    }
}
