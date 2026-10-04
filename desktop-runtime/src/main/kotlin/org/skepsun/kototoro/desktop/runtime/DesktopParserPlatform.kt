package org.skepsun.kototoro.desktop.runtime

import okhttp3.CookieJar
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.skepsun.kototoro.core.source.SourcePreferenceStore
import org.skepsun.kototoro.parserhost.ParserPlatform
import org.skepsun.kototoro.parsers.bitmap.Bitmap
import org.skepsun.kototoro.parsers.bitmap.Rect
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import javax.imageio.ImageIO

/** Executes page scripts for parsers; the Windows app backs it with the WebView2 bridge. */
fun interface DesktopScriptEvaluator {
    suspend fun evaluate(baseUrl: String, script: String): String?
}

/**
 * Parser host services on Windows. [client] is derived from the platform HTTP client (shared cookie store and web
 * challenge handling); image descrambling runs on AWT bitmaps.
 */
class DesktopParserPlatform(
    private val client: OkHttpClient,
    override val preferences: SourcePreferenceStore,
    private val scripts: DesktopScriptEvaluator? = null,
) : ParserPlatform {
    override val httpClient: OkHttpClient get() = client
    override val cookieJar: CookieJar get() = client.cookieJar

    override fun createBitmap(width: Int, height: Int): Bitmap = AwtBitmap(BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB))

    override fun redrawImageResponse(response: Response, redraw: (Bitmap) -> Bitmap): Response =
        AwtBitmap.redraw(response, redraw)

    override suspend fun evaluateJs(baseUrl: String, script: String): String? =
        scripts?.evaluate(baseUrl, script) ?: super.evaluateJs(baseUrl, script)
}

/** A mutable ARGB image; pixel-exact block copies are what scrambled-image parsers rely on. */
internal class AwtBitmap(val image: BufferedImage) : Bitmap {
    override val width: Int get() = image.width
    override val height: Int get() = image.height

    override fun drawBitmap(sourceBitmap: Bitmap, src: Rect, dst: Rect) {
        val source = requireNotNull((sourceBitmap as? AwtBitmap)?.image) { "Bitmap was not created by this host" }
        val graphics = image.createGraphics()
        try {
            graphics.drawImage(source, dst.left, dst.top, dst.right, dst.bottom, src.left, src.top, src.right, src.bottom, null)
        } finally {
            graphics.dispose()
        }
    }

    companion object {
        private const val MAXIMUM_BYTES = 64L * 1024 * 1024

        /** Decodes the body, lets [redraw] rearrange it and returns the result as PNG. */
        fun redraw(response: Response, redraw: (Bitmap) -> Bitmap): Response {
            val bytes = response.use { source ->
                val body = source.body
                if (body.contentLength() > MAXIMUM_BYTES) throw IOException("Image exceeds configured limit")
                body.bytes().also { if (it.size > MAXIMUM_BYTES) throw IOException("Image exceeds configured limit") }
            }
            val decoded = ImageIO.read(ByteArrayInputStream(bytes)) ?: throw IOException("Unsupported image format")
            // Parsers draw into the bitmap they receive, so it must be a mutable ARGB copy.
            val mutable = BufferedImage(decoded.width, decoded.height, BufferedImage.TYPE_INT_ARGB).also { copy ->
                copy.createGraphics().apply { drawImage(decoded, 0, 0, null); dispose() }
            }
            val result = requireNotNull((redraw(AwtBitmap(mutable)) as? AwtBitmap)?.image) {
                "Image redraw must return a bitmap created by this host"
            }
            val output = ByteArrayOutputStream()
            check(ImageIO.write(result, "png", output)) { "PNG encoder unavailable" }
            return response.newBuilder()
                .removeHeader("Content-Length")
                .header("Content-Type", "image/png")
                .body(output.toByteArray().toResponseBody("image/png".toMediaType()))
                .build()
        }
    }
}
