package org.skepsun.kototoro.local.data

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import coil3.ImageLoader
import coil3.request.CachePolicy
import coil3.request.ErrorResult
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.inject.Inject
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.skepsun.kototoro.core.util.ext.toZipUri
import org.skepsun.kototoro.local.data.input.LocalContentParser

/** Parses a real CBZ and decodes its published cover/page URLs through the production Coil loader. */
@RunWith(AndroidJUnit4::class)
@HiltAndroidTest
class LocalArchiveImageTest {

    @get:Rule
    val hiltRule = HiltAndroidRule(this)

    @Inject
    lateinit var imageLoader: ImageLoader

    @Test
    fun fileParserPublishesDecodableArchiveImages() = verifyArchive(useDirectZipUri = false)

    @Test
    fun archiveFetcherDecodesPngEntry() = verifyArchive(useDirectZipUri = true)

    private fun verifyArchive(useDirectZipUri: Boolean) = runBlocking {
        hiltRule.inject()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val archive = File.createTempFile("archive_image_", ".cbz", context.cacheDir)
        val bitmap = Bitmap.createBitmap(32, 48, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLUE) }
        try {
            ZipOutputStream(archive.outputStream()).use { zip ->
                zip.putNextEntry(ZipEntry("Chapter 01/01.png"))
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, zip)
                zip.closeEntry()
            }
            val urls = if (useDirectZipUri) {
                listOf(archive.toZipUri("Chapter 01/01.png").toString())
            } else {
                val parser = LocalContentParser(archive)
                val content = parser.getContent(withDetails = true).manga
                val chapter = requireNotNull(content.chapters).single()
                listOf(requireNotNull(content.coverUrl), parser.getPages(chapter).single().url)
            }
            urls.forEach { url ->
                val result = imageLoader.execute(
                    ImageRequest.Builder(context)
                        .data(url)
                        .memoryCachePolicy(CachePolicy.DISABLED)
                        .diskCachePolicy(CachePolicy.DISABLED)
                        .build(),
                )
                assertTrue("Cannot decode $url: ${(result as? ErrorResult)?.throwable}", result is SuccessResult)
                val image = (result as SuccessResult).image
                assertEquals(32, image.width)
                assertEquals(48, image.height)
            }
        } finally {
            bitmap.recycle()
            archive.delete()
        }
    }
}
