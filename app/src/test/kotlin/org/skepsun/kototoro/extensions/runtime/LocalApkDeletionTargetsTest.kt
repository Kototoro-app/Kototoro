package org.skepsun.kototoro.extensions.runtime

import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class LocalApkDeletionTargetsTest {

    @Test
    fun `an IReader repository directory is found by the package its archive declares`(@TempDir root: File) {
        // Repository installs store the APK under the repository id; the loaded package name differs.
        val dir = File(root, "ireader-en-novelfull").apply { mkdirs() }
        val apk = File(dir, "ireader-en-novelfull.apk").apply { writeText("apk") }

        val targets = LocalApkExtensionSupport.managedDeletionTargets(
            root = root,
            packageName = "ireader.novelfull.en",
            archives = listOf(apk to "ireader.novelfull.en"),
        )

        assertEquals(listOf(dir.absoluteFile), targets)
    }

    @Test
    fun `a hand-copied archive with its own file name is found by its package`(@TempDir root: File) {
        val apk = File(root, "tachiyomi-zh.copymanga-v1.4.apk").apply { writeText("apk") }
        val other = File(root, "eu.kanade.tachiyomi.extension.en.other.apk").apply { writeText("apk") }

        val targets = LocalApkExtensionSupport.managedDeletionTargets(
            root = root,
            packageName = "eu.kanade.tachiyomi.extension.zh.copymanga",
            archives = listOf(
                apk to "eu.kanade.tachiyomi.extension.zh.copymanga",
                other to "eu.kanade.tachiyomi.extension.en.other",
            ),
        )

        assertEquals(listOf(apk.absoluteFile), targets)
    }

    @Test
    fun `archives named after the package are still matched without a parsed entry`(@TempDir root: File) {
        val pkg = "eu.kanade.tachiyomi.extension.en.sample"
        val dir = File(root, pkg).apply { mkdirs() }
        File(dir, "$pkg.apk").writeText("apk")
        val direct = File(root, "$pkg.apk").apply { writeText("apk") }

        val targets = LocalApkExtensionSupport.managedDeletionTargets(root, pkg, archives = emptyList())

        assertEquals(setOf(dir.absoluteFile, direct.absoluteFile), targets.toSet())
    }
}
