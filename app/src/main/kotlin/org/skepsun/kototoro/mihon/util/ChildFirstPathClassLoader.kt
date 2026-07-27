package org.skepsun.kototoro.mihon.util

import dalvik.system.DexClassLoader
import java.io.File

/**
 * A ClassLoader that loads classes from its own path before delegating to its parent.
 * 
 * This is necessary for Mihon extensions because they may bundle different versions
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

    /**
     * List of packages that should always be loaded from the parent ClassLoader.
     * These are core Android/Kotlin classes and Mihon API classes that must be shared.
     */
    override fun loadClass(name: String, resolve: Boolean): Class<*> {
        // Check if we should delegate to parent immediately
        if (ChildFirstClassLoaderPolicy.shouldDelegateToParent(name)) {
            return parent.loadClass(name)
        }

        // Try to find the class in our own path first
        return try {
            findLoadedClass(name) ?: findClass(name)
        } catch (e: ClassNotFoundException) {
            // Fall back to parent ClassLoader
            parent.loadClass(name)
        }
    }
}
