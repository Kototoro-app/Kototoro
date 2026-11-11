package org.skepsun.kototoro.local.data

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.core.net.toUri
import org.skepsun.kototoro.parsers.model.ContentPage
import org.skepsun.kototoro.parsers.model.ContentSource
import org.skepsun.kototoro.parsers.util.longHashCode
import java.io.File
import java.io.IOException
import java.util.LinkedHashMap
import kotlin.math.max
import kotlin.math.roundToInt

const val URI_SCHEME_PDF = "file+pdf"
const val URI_SCHEME_CONTENT_PDF = "content+pdf"

private const val DEFAULT_PDF_RENDER_SCALE = 2f
private const val DEFAULT_MAX_PDF_PAGE_DIMENSION_PX = 2560

fun hasPdfExtension(name: String?): Boolean {
    return name?.substringAfterLast('.', "")?.equals("pdf", ignoreCase = true) == true
}

fun File.hasPdfSignature(): Boolean {
    if (!isFile || length() < 5L) {
        return false
    }
    return runCatching {
        inputStream().use { input ->
            val header = ByteArray(5)
            val read = input.read(header)
            read == 5 &&
                header[0] == '%'.code.toByte() &&
                header[1] == 'P'.code.toByte() &&
                header[2] == 'D'.code.toByte() &&
                header[3] == 'F'.code.toByte() &&
                header[4] == '-'.code.toByte()
        }
    }.getOrDefault(false)
}

val File.isPdfDocument: Boolean
    get() = isFile && (hasPdfExtension(name) || (extension.isEmpty() && hasPdfSignature()))

fun Uri.isPdfPageUri(): Boolean = scheme == URI_SCHEME_PDF || scheme == URI_SCHEME_CONTENT_PDF

fun Uri.isFilePdfUri(): Boolean = scheme == URI_SCHEME_PDF

fun Uri.isContentPdfUri(): Boolean = scheme == URI_SCHEME_CONTENT_PDF

fun Uri.pdfPageIndex(): Int? = fragment?.toIntOrNull()?.takeIf { it >= 0 }

fun File.toPdfPageUri(pageIndex: Int): Uri {
    require(pageIndex >= 0) { "PDF page index must not be negative: $pageIndex" }
    return toUri().buildUpon()
        .scheme(URI_SCHEME_PDF)
        .fragment(pageIndex.toString())
        .build()
}

fun Uri.toContentPdfPageUri(pageIndex: Int): Uri {
    require(pageIndex >= 0) { "PDF page index must not be negative: $pageIndex" }
    return buildUpon()
        .scheme(URI_SCHEME_CONTENT_PDF)
        .fragment(pageIndex.toString())
        .build()
}

fun Uri.toUnderlyingPdfUri(): Uri = when (scheme) {
    URI_SCHEME_PDF -> buildUpon().scheme("file").fragment(null).build()
    URI_SCHEME_CONTENT_PDF -> buildUpon().scheme("content").fragment(null).build()
    else -> this
}

fun Uri.toSourceContentPdfUri(): Uri = toUnderlyingPdfUri()

object LocalPdfPages {

    private const val MAX_CACHED_PDF_RENDERERS = 4

    private data class FileStamp(val lastModified: Long, val length: Long)

    private class CachedPdfRenderer(
        val renderer: PdfRenderer,
        val fileStamp: FileStamp?,
    ) {
        val renderLock = Any()
        var activeCount = 0
        var evicted = false
    }

    private val cacheLock = Any()
    private val pdfRenderers = LinkedHashMap<String, CachedPdfRenderer>(MAX_CACHED_PDF_RENDERERS, 0.75f, true)

    fun isPdfPageUri(uri: Uri): Boolean = uri.isPdfPageUri()

    fun isPdfDocument(file: File): Boolean = file.isPdfDocument

    fun getPageCount(file: File): Int {
        return withCachedRenderer(
            key = file.toUri().toString(),
            expectedStamp = file.currentStamp(),
            openDescriptor = { ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY) },
        ) { renderer ->
            renderer.pageCount
        }
    }

    fun getPageCount(contentResolver: ContentResolver, uri: Uri): Int {
        val underlying = uri.toUnderlyingPdfUri()
        return withCachedRenderer(
            key = underlying.toString(),
            expectedStamp = null,
            openDescriptor = {
                contentResolver.openFileDescriptor(underlying, "r")
                    ?: throw IOException("Unable to open PDF descriptor: $uri")
            },
        ) { renderer ->
            renderer.pageCount
        }
    }

    fun buildFilePages(file: File, source: ContentSource): List<ContentPage> {
        val count = getPageCount(file)
        return List(count) { pageIndex ->
            val pageUrl = file.toPdfPageUri(pageIndex).toString()
            ContentPage(
                id = pageUrl.longHashCode(),
                url = pageUrl,
                preview = null,
                source = source,
            )
        }
    }

    fun buildContentPages(contentResolver: ContentResolver, uri: Uri, source: ContentSource): List<ContentPage> {
        val baseUri = uri.buildUpon().fragment(null).build()
        val count = getPageCount(contentResolver, baseUri)
        return List(count) { pageIndex ->
            val pageUrl = baseUri.toContentPdfPageUri(pageIndex).toString()
            ContentPage(
                id = pageUrl.longHashCode(),
                url = pageUrl,
                preview = null,
                source = source,
            )
        }
    }

    fun computeTargetDimensions(
        pageWidthPts: Int,
        pageHeightPts: Int,
        maxDimensionPx: Int = DEFAULT_MAX_PDF_PAGE_DIMENSION_PX,
    ): Pair<Int, Int> {
        val safeWidth = pageWidthPts.coerceAtLeast(1)
        val safeHeight = pageHeightPts.coerceAtLeast(1)
        val baseWidth = safeWidth * DEFAULT_PDF_RENDER_SCALE
        val baseHeight = safeHeight * DEFAULT_PDF_RENDER_SCALE
        val longestSide = max(baseWidth, baseHeight)
        val clampScale = if (longestSide > maxDimensionPx) {
            maxDimensionPx.toFloat() / longestSide
        } else {
            1f
        }
        val targetWidth = (baseWidth * clampScale).roundToInt().coerceAtLeast(1)
        val targetHeight = (baseHeight * clampScale).roundToInt().coerceAtLeast(1)
        return targetWidth to targetHeight
    }

    fun renderPage(
        contentResolver: ContentResolver,
        pageUri: Uri,
        maxDimensionPx: Int = DEFAULT_MAX_PDF_PAGE_DIMENSION_PX,
    ): Bitmap {
        val pageIndex = requireNotNull(pageUri.pdfPageIndex()) {
            "PDF page URI has no valid page index: $pageUri"
        }
        val underlying = pageUri.toUnderlyingPdfUri()
        val backingFile = if (pageUri.scheme == URI_SCHEME_PDF) {
            val path = requireNotNull(underlying.path) {
                "PDF file URI path is null: $pageUri"
            }
            File(path)
        } else {
            null
        }
        return withCachedRenderer(
            key = underlying.toString(),
            expectedStamp = backingFile?.currentStamp(),
            openDescriptor = {
                when (pageUri.scheme) {
                    URI_SCHEME_PDF -> ParcelFileDescriptor.open(
                        requireNotNull(backingFile),
                        ParcelFileDescriptor.MODE_READ_ONLY,
                    )

                    URI_SCHEME_CONTENT_PDF -> {
                        contentResolver.openFileDescriptor(underlying, "r")
                            ?: throw IOException("Unable to open PDF content URI: $pageUri")
                    }

                    else -> throw IllegalArgumentException("Unsupported PDF page URI: $pageUri")
                }
            },
        ) { renderer ->
            if (pageIndex !in 0 until renderer.pageCount) {
                throw IndexOutOfBoundsException(
                    "PDF page index $pageIndex out of bounds (count=${renderer.pageCount})",
                )
            }
            renderer.openPage(pageIndex).use { page ->
                val (width, height) = computeTargetDimensions(
                    pageWidthPts = page.width,
                    pageHeightPts = page.height,
                    maxDimensionPx = maxDimensionPx.coerceAtLeast(64),
                )
                val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                try {
                    bitmap.eraseColor(Color.WHITE)
                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    bitmap
                } catch (e: Throwable) {
                    bitmap.recycle()
                    throw e
                }
            }
        }
    }

    /** Drops the cached renderer for [file] once any in-flight page render finishes. */
    fun evictPdfRenderer(file: File) {
        evictPdfRenderer(file.toUri())
    }

    /** Drops the cached renderer for [pageUri] once any in-flight page render finishes. */
    fun evictPdfRenderer(pageUri: Uri) {
        val toClose = synchronized(cacheLock) {
            pdfRenderers.remove(pageUri.toUnderlyingPdfUri().toString())?.also { entry ->
                entry.evicted = true
            }?.takeIf { it.activeCount == 0 }
        }
        toClose?.renderer?.close()
    }

    /** Drops cached renderers for [rootUri] itself and any child PDF documents beneath it. */
    fun evictPdfRenderersUnder(rootUri: Uri) {
        val base = rootUri.toUnderlyingPdfUri().toString().trimEnd('/')
        if (base.isEmpty()) return
        val slashPrefix = "$base/"
        val encodedSlashUpper = "$base%2F"
        val encodedSlashLower = "$base%2f"
        val idleToClose = synchronized(cacheLock) {
            val matchingKeys = pdfRenderers.keys.filter { key ->
                key == base ||
                    key.startsWith(slashPrefix) ||
                    key.startsWith(encodedSlashUpper) ||
                    key.startsWith(encodedSlashLower)
            }
            matchingKeys.mapNotNull { key ->
                pdfRenderers.remove(key)?.also { entry ->
                    entry.evicted = true
                }?.takeIf { it.activeCount == 0 }
            }
        }
        idleToClose.forEach { it.renderer.close() }
    }

    /** Drops all cached renderers and closes idle descriptors immediately. */
    fun clearPdfRenderers() {
        val idle = synchronized(cacheLock) {
            val readyToClose = pdfRenderers.values.filter { entry ->
                entry.evicted = true
                entry.activeCount == 0
            }
            pdfRenderers.clear()
            readyToClose
        }
        idle.forEach { it.renderer.close() }
    }

    internal fun putRendererForTest(uri: Uri, renderer: PdfRenderer) {
        synchronized(cacheLock) {
            pdfRenderers[uri.toUnderlyingPdfUri().toString()] = CachedPdfRenderer(renderer, null)
        }
    }

    internal fun hasCachedRenderer(uri: Uri): Boolean = synchronized(cacheLock) {
        val entry = pdfRenderers[uri.toUnderlyingPdfUri().toString()]
        entry != null && !entry.evicted
    }

    private fun File.currentStamp(): FileStamp = FileStamp(lastModified(), length())

    private inline fun <T> withCachedRenderer(
        key: String,
        expectedStamp: FileStamp?,
        openDescriptor: () -> ParcelFileDescriptor,
        block: (PdfRenderer) -> T,
    ): T {
        var staleIdleToClose: CachedPdfRenderer? = null
        val entry = synchronized(cacheLock) {
            val existing = pdfRenderers[key]
            if (existing != null && !existing.evicted) {
                if (expectedStamp != null && existing.fileStamp != expectedStamp) {
                    pdfRenderers.remove(key)
                    existing.evicted = true
                    if (existing.activeCount == 0) {
                        staleIdleToClose = existing
                    }
                    null
                } else {
                    existing.activeCount++
                    existing
                }
            } else {
                null
            }
        }?.also {
            staleIdleToClose?.renderer?.close()
        } ?: run {
            staleIdleToClose?.renderer?.close()
            val descriptor = openDescriptor()
            val created = try {
                PdfRenderer(descriptor)
            } catch (e: Throwable) {
                descriptor.close()
                throw e
            }
            var duplicateToClose: PdfRenderer? = null
            var evictedIdle: CachedPdfRenderer? = null
            val chosen = synchronized(cacheLock) {
                val raced = pdfRenderers[key]
                if (raced != null && !raced.evicted && (expectedStamp == null || raced.fileStamp == expectedStamp)) {
                    raced.activeCount++
                    duplicateToClose = created
                    raced
                } else {
                    if (raced != null) {
                        pdfRenderers.remove(key)
                        raced.evicted = true
                        if (raced.activeCount == 0) {
                            evictedIdle = raced
                        }
                    } else if (pdfRenderers.size >= MAX_CACHED_PDF_RENDERERS) {
                        val eldestKey = pdfRenderers.keys.first()
                        val eldest = pdfRenderers.remove(eldestKey)
                        if (eldest != null) {
                            eldest.evicted = true
                            if (eldest.activeCount == 0) {
                                evictedIdle = eldest
                            }
                        }
                    }
                    CachedPdfRenderer(created, expectedStamp).also {
                        it.activeCount = 1
                        pdfRenderers[key] = it
                    }
                }
            }
            duplicateToClose?.close()
            evictedIdle?.renderer?.close()
            chosen
        }
        return try {
            synchronized(entry.renderLock) {
                block(entry.renderer)
            }
        } finally {
            val closeAfterUse = synchronized(cacheLock) {
                entry.activeCount--
                entry.evicted && entry.activeCount == 0
            }
            if (closeAfterUse) {
                entry.renderer.close()
            }
        }
    }
}
