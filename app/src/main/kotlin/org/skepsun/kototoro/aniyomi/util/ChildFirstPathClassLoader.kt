package org.skepsun.kototoro.aniyomi.util

import dalvik.system.DexClassLoader
import java.io.File

/**
 * A ClassLoader that loads classes from its own path before delegating to its parent.
 *
 * This is necessary for Aniyomi extensions because they may bundle different versions
 * of libraries than Kototoro uses, and we need to isolate them.
 */
class ChildFirstPathClassLoader(
    dexPath: String,
    librarySearchPath: String?,
    parent: ClassLoader,
) : DexClassLoader(
    dexPath,
    File(dexPath).parentFile?.absolutePath,
    librarySearchPath,
    parent,
) {

    init {
        android.util.Log.i("ChildFirstPathClassLoader", "Created for path: $dexPath")
    }


    /**
     * List of packages that should always be loaded from the parent ClassLoader.
     * These are core Android/Kotlin classes and Aniyomi API classes that must be shared.
     */
    private val parentPackages = setOf(
        "java.",
        "javax.",
        "kotlin.",
        "kotlinx.",
        "android.",
        "androidx.",
        "org.json.",
        "org.jsoup.",
        "okhttp3.",
        "okio.",
        "rx.",
        "eu.kanade.tachiyomi.animesource.",
        "eu.kanade.tachiyomi.network.",
        "eu.kanade.tachiyomi.torrentutils.",
        "eu.kanade.tachiyomi.util.",
        "uy.kohesive.injekt.",
    )

    private val systemClassLoader: ClassLoader? = getSystemClassLoader()

    override fun loadClass(name: String?, resolve: Boolean): Class<*> {
        if (name == null) return super.loadClass(name, resolve)

        var c = findLoadedClass(name)

        // 1. Try system class loader (core java/android classes)
        if (c == null && systemClassLoader != null) {
            try {
                c = systemClassLoader.loadClass(name)
            } catch (e: ClassNotFoundException) {
                // Ignore
            }
        }

        // 2. Try parent packages (Kototoro API classes that must be shared)
        if (c == null && parentPackages.any { name.startsWith(it) }) {
            c = try {
                parent.loadClass(name)
            } catch (e: ClassNotFoundException) {
                null
            }
        }

        // 3. Try to find the class in our own path
        if (c == null) {
            c = try {
                findClass(name)
            } catch (e: ClassNotFoundException) {
                // 4. Fall back to parent ClassLoader for everything else
                try {
                    parent.loadClass(name)
                } catch (e2: ClassNotFoundException) {
                    android.util.Log.w("ChildFirstPathClassLoader", "Class not found: $name in both child and parent")
                    throw e2
                }
            }
        }

        if (resolve && c != null) {
            resolveClass(c)
        }

        return c ?: throw ClassNotFoundException(name)
    }
}
