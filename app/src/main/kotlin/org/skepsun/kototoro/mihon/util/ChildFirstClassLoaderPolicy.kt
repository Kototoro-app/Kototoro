package org.skepsun.kototoro.mihon.util

import org.skepsun.kototoro.extensions.runtime.tachiyomi.TachiyomiApkClassLoaderPolicy

/**
 * Legacy alias for the shared [TachiyomiApkClassLoaderPolicy]. Kept under the original package
 * so existing consumers ([ChildFirstPathClassLoader], tests) keep working while every
 * Tachiyomi-ABI ecosystem shares one policy.
 */
@Deprecated("Use TachiyomiApkClassLoaderPolicy from extensions/runtime/tachiyomi instead")
internal object ChildFirstClassLoaderPolicy {

    fun shouldDelegateToParent(className: String): Boolean =
        TachiyomiApkClassLoaderPolicy.shouldDelegateToParent(className)
}
