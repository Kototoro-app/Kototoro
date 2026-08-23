package org.skepsun.kototoro.extensions.runtime.tachiyomi

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TachiyomiApkClassLoaderPolicyTest {

    @Test
    fun `platform and stdlib classes always delegate to parent`() {
        assertTrue(TachiyomiApkClassLoaderPolicy.shouldDelegateToParent("java.lang.String"))
        assertTrue(TachiyomiApkClassLoaderPolicy.shouldDelegateToParent("kotlinx.coroutines.BuildersKt"))
        assertTrue(TachiyomiApkClassLoaderPolicy.shouldDelegateToParent("android.content.Context"))
        assertTrue(TachiyomiApkClassLoaderPolicy.shouldDelegateToParent("androidx.core.content.pm.PackageInfoCompat"))
        assertTrue(TachiyomiApkClassLoaderPolicy.shouldDelegateToParent("okhttp3.OkHttpClient"))
        assertTrue(TachiyomiApkClassLoaderPolicy.shouldDelegateToParent("uy.kohesive.injekt.api.Injekt"))
    }

    @Test
    fun `host-owned manga ABI delegates to parent`() {
        assertTrue(TachiyomiApkClassLoaderPolicy.shouldDelegateToParent("eu.kanade.tachiyomi.source.Source"))
        assertTrue(TachiyomiApkClassLoaderPolicy.shouldDelegateToParent("eu.kanade.tachiyomi.source.online.HttpSource"))
        assertTrue(TachiyomiApkClassLoaderPolicy.shouldDelegateToParent("eu.kanade.tachiyomi.source.model.Page"))
        assertTrue(TachiyomiApkClassLoaderPolicy.shouldDelegateToParent("eu.kanade.tachiyomi.network.NetworkHelper"))
    }

    @Test
    fun `host-owned novel ABI delegates to parent`() {
        assertTrue(TachiyomiApkClassLoaderPolicy.shouldDelegateToParent("eu.kanade.tachiyomi.source.NovelSource"))
        assertTrue(TachiyomiApkClassLoaderPolicy.shouldDelegateToParent("eu.kanade.tachiyomi.source.SourceTracker"))
        assertTrue(TachiyomiApkClassLoaderPolicy.shouldDelegateToParent("eu.kanade.tachiyomi.source.RateLimited"))
        assertTrue(TachiyomiApkClassLoaderPolicy.shouldDelegateToParent("eu.kanade.tachiyomi.source.model.RefreshContext"))
    }

    @Test
    fun `extension implementation classes load child-first`() {
        assertFalse(TachiyomiApkClassLoaderPolicy.shouldDelegateToParent("eu.kanade.tachiyomi.extension.en.novel.ExampleNovel"))
        assertFalse(TachiyomiApkClassLoaderPolicy.shouldDelegateToParent("com.example.ExtensionRuntime"))
        // jsoup is host-provided, so it delegates to parent.
        assertTrue(TachiyomiApkClassLoaderPolicy.shouldDelegateToParent("org.jsoup.nodes.Document"))
    }

    @Test
    fun `default-method bridge classes load child-first while their interfaces stay parent-bound`() {
        // NovelSourcery/Tsundoku extensions bundle `-CC` and `DefaultImpls` bridge classes
        // inside their own APK (produced by D8/javac default-method interop). The host has no
        // equivalent, so they must resolve from the extension even though they live in the
        // host-owned `eu.kanade.tachiyomi.source` package.
        assertFalse(TachiyomiApkClassLoaderPolicy.shouldDelegateToParent("eu.kanade.tachiyomi.source.NovelSource$-CC"))
        assertFalse(TachiyomiApkClassLoaderPolicy.shouldDelegateToParent("eu.kanade.tachiyomi.source.SourceTracker$-CC"))
        assertFalse(TachiyomiApkClassLoaderPolicy.shouldDelegateToParent("eu.kanade.tachiyomi.source.NovelSource\$DefaultImpls"))
        assertFalse(TachiyomiApkClassLoaderPolicy.shouldDelegateToParent("eu.kanade.tachiyomi.source.SourceTracker\$DefaultImpls"))
        // The interfaces/bridges' target ABI classes themselves stay host-bound.
        assertTrue(TachiyomiApkClassLoaderPolicy.shouldDelegateToParent("eu.kanade.tachiyomi.source.NovelSource"))
        assertTrue(TachiyomiApkClassLoaderPolicy.shouldDelegateToParent("eu.kanade.tachiyomi.source.SourceTracker"))
    }

    @Test
    fun `legacy alias still delegates through the shared policy`() {
        assertTrue(org.skepsun.kototoro.mihon.util.ChildFirstClassLoaderPolicy.shouldDelegateToParent("eu.kanade.tachiyomi.source.Source"))
        assertFalse(org.skepsun.kototoro.mihon.util.ChildFirstClassLoaderPolicy.shouldDelegateToParent("eu.kanade.tachiyomi.extension.en.novel.ExampleNovel"))
    }
}
