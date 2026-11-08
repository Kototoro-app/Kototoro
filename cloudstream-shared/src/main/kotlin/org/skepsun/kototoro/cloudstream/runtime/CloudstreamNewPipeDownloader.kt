package org.skepsun.kototoro.cloudstream.runtime

import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import java.util.concurrent.TimeUnit

/**
 * NewPipeExtractor's HTTP layer on the app's content client (proxy, DNS, cookies), mirroring the official Cloudstream
 * app's `DownloaderTestImpl`: Cloudstream's YouTube extractor and YouTube plugins fetch every page through it. Android
 * and Windows both install it at start-up.
 */
class CloudstreamNewPipeDownloader(client: OkHttpClient) : Downloader() {

    private val client = client.newBuilder()
        .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()

    override fun execute(request: Request): Response {
        val url = request.url()
        val body = request.dataToSend()?.toRequestBody()
        val builder = okhttp3.Request.Builder()
            .method(request.httpMethod(), body)
            .url(url)
            .header(USER_AGENT_HEADER, com.lagradost.cloudstream3.USER_AGENT)
        for ((name, values) in request.headers()) {
            builder.removeHeader(name)
            values.forEach { value -> builder.addHeader(name, value) }
        }
        client.newCall(builder.build()).execute().use { response ->
            if (response.code == HTTP_TOO_MANY_REQUESTS) {
                throw ReCaptchaException("reCaptcha Challenge requested", url)
            }
            return Response(
                response.code,
                response.message,
                response.headers.toMultimap(),
                response.body.string(),
                response.request.url.toString(),
            )
        }
    }

    private companion object {
        const val READ_TIMEOUT_SECONDS = 30L
        const val HTTP_TOO_MANY_REQUESTS = 429
        const val USER_AGENT_HEADER = "User-Agent"
    }
}
