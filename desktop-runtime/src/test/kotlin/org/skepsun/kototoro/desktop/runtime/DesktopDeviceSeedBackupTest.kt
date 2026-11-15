package org.skepsun.kototoro.desktop.runtime

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.core.source.SourceChapter
import org.skepsun.kototoro.core.source.SourceContent
import org.skepsun.kototoro.core.source.SourceRef
import org.skepsun.kototoro.core.source.SourceTag
import java.nio.file.Files
import java.nio.file.Path

/**
 * Emits a small library backup that the Android emulator can import, so device checks of the bookshelf,
 * history and details pages run against real content instead of empty holders.
 *
 * Skipped unless `KOTOTORO_SEED_BACKUP` names the target ZIP: it writes outside the temp directory, and a
 * test run must not leave artefacts behind on its own. Usage:
 * `KOTOTORO_SEED_BACKUP=build/device-reports/seed/kototoro-seed.bk.zip ./gradlew :desktop-runtime:test --tests "*DesktopDeviceSeedBackupTest"`.
 */
class DesktopDeviceSeedBackupTest {

    @Test
    fun `seeds a library backup for device checks`() = runBlocking<Unit> {
        val targetPath = System.getenv("KOTOTORO_SEED_BACKUP")
        Assumptions.assumeTrue(targetPath != null, "KOTOTORO_SEED_BACKUP is not set")
        val target = Path.of(targetPath).toAbsolutePath().normalize()
        Files.createDirectories(target.parent)
        Files.deleteIfExists(target)
        val root = Files.createTempDirectory("kototoro-device-seed-")
        try {
            val source = SourceRef("MIHON_7000000000000000001", "en", "MANGA")
            val novelSource = SourceRef("MIHON_7000000000000000002", "en", "NOVEL")
            DesktopRuntime.open(root.resolve("db")).use { runtime ->
                val library = DesktopLibrary(runtime.database) { source }
                val mangaCategory = library.createCategory("Manga shelf")
                val novelCategory = library.createCategory("Novels")
                seedContents(source, novelSource).forEachIndexed { index, content ->
                    library.save(content)
                    library.addFavourite(content)
                    library.setCategories(content, setOf(if (index % 4 == 3) novelCategory else mangaCategory))
                    val chapter = requireNotNull(content.chapters?.firstOrNull())
                    val pages = if (content.source.contentType.startsWith("NOVEL")) 1 else 8
                    library.recordPage(content, chapter, page = maxOf(pages - 2, 0), pageCount = pages)
                }
                DesktopLibraryBackup(runtime.database).export(target)
            }
        } finally {
            root.toFile().deleteRecursively()
        }
        assertTrue(Files.size(target) > 0L)
    }

    private fun seedContents(source: SourceRef, novelSource: SourceRef): List<SourceContent> = listOf(
        "The Long Way Home",
        "A Rather Very Long Manga Title That Wraps",
        "Short",
        "Nightly Novel",
        "Second Collection",
        "Yet Another Title",
    ).mapIndexed { index, title ->
        val isNovel = index % 4 == 3
        val chapterCount = if (isNovel) 4 else 6
        val contentSource = if (isNovel) novelSource else source
        SourceContent(
            id = 1_000L + index,
            title = title,
            altTitles = emptySet(),
            url = "/work/$index",
            publicUrl = "https://fixture.invalid/work/$index",
            rating = 0.6f,
            contentRating = null,
            coverUrl = "https://fixture.invalid/cover/$index.jpg",
            tags = setOf(SourceTag("Drama", "drama", contentSource)),
            state = null,
            authors = setOf("Fixture Author"),
            source = contentSource,
            description = "Fixture description for $title.",
            chapters = (0 until chapterCount).map { chapterIndex ->
                SourceChapter(
                    id = 2_000L + index * 10 + chapterIndex,
                    title = "Chapter ${chapterIndex + 1}",
                    number = (chapterIndex + 1).toFloat(),
                    volume = 0,
                    url = "/work/$index/chapter/$chapterIndex",
                    scanlator = null,
                    uploadDate = 1_700_000_000_000L + chapterIndex * 86_400_000L,
                    branch = null,
                    source = contentSource,
                )
            },
        )
    }
}
