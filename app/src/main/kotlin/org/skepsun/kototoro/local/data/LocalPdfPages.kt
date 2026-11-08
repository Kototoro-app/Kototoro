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

    fun isPdfPageUri(uri: Uri): Boolean = uri.isPdfPageUri()

    fun isPdfDocument(file: File): Boolean = file.isPdfDocument

    fun getPageCount(file: File): Int {
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
            PdfRenderer(descriptor).use { renderer -> renderer.pageCount }
        }
    }

    fun getPageCount(contentResolver: ContentResolver, uri: Uri): Int {
        val descriptor = contentResolver.openFileDescriptor(uri.toUnderlyingPdfUri(), "r")
            ?: throw IOException("Unable to open PDF descriptor: $uri")
        return descriptor.use { pfd ->
            PdfRenderer(pfd).use { renderer -> renderer.pageCount }
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
        val descriptor = when (pageUri.scheme) {
            URI_SCHEME_PDF -> {
                val path = requireNotNull(pageUri.toUnderlyingPdfUri().path) {
                    "PDF file URI path is null: $pageUri"
                }
                ParcelFileDescriptor.open(File(path), ParcelFileDescriptor.MODE_READ_ONLY)
            }

            URI_SCHEME_CONTENT_PDF -> {
                contentResolver.openFileDescriptor(pageUri.toUnderlyingPdfUri(), "r")
                    ?: throw IOException("Unable to open PDF content URI: $pageUri")
            }

            else -> throw IllegalArgumentException("Unsupported PDF page URI: $pageUri")
        }
        return descriptor.use { pfd ->
            PdfRenderer(pfd).use { renderer ->
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
    }
}
