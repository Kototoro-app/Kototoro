package org.skepsun.kototoro.backups.webdav

import io.ktor.client.HttpClient
import io.ktor.client.plugins.expectSuccess
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.header
import io.ktor.client.request.prepareRequest
import io.ktor.client.request.setBody
import io.ktor.client.request.url
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.content.OutgoingContent
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.ByteWriteChannel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive

class WebDavEndpoint(
    serverUrl: String,
    remotePath: String,
    val authorization: () -> String? = { null },
) {
    private val directory = serverUrl.trimEnd('/') + remotePath.trim('/').let { if (it.isEmpty()) "" else "/$it" } + "/"

    // All writer generations share the same directory; their filename prefixes separate them.
    fun url(fileName: String? = null): String = directory + fileName.orEmpty()
}

/** The platform owns clients, XML parsing and file streams; every response is scoped to its operation. */
class WebDavBackupClient(
    private val metadataClient: HttpClient,
    private val transferClient: HttpClient,
    private val parseResources: (String) -> List<WebDavResource>,
) {
    suspend fun probe(endpoint: WebDavEndpoint) {
        propfind(endpoint, "0", "<D:displayname/>").execute(metadataClient) { it.requireSuccess("connection") }
    }

    suspend fun list(endpoint: WebDavEndpoint): List<WebDavBackupFile> =
        propfind(endpoint, "1", "<D:displayname/><D:getlastmodified/><D:getcontentlength/>")
            .execute(metadataClient) { response ->
                if (response.status.value == 404) emptyList() else {
                    response.requireSuccess("PROPFIND")
                    WebDavBackupCatalog.classify(parseResources(response.bodyAsText()))
                }
            }

    suspend fun upload(
        endpoint: WebDavEndpoint,
        fileName: String,
        size: Long,
        writeFile: suspend (ByteWriteChannel) -> Unit,
    ) {
        repeat(3) { attempt ->
            currentCoroutineContext().ensureActive()
            try {
                request(endpoint, fileName, HttpMethod.Put).apply {
                    setBody(object : OutgoingContent.WriteChannelContent() {
                        override val contentType: ContentType = ContentType.parse("application/zip")
                        override val contentLength: Long = size
                        override suspend fun writeTo(channel: ByteWriteChannel) = writeFile(channel)
                    })
                }.execute(transferClient) { response ->
                    if (!response.status.isSuccess()) {
                        val detail = try {
                            response.bodyAsText().trim().let { if (it.length > 1024) it.take(1024) + "..." else it }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (_: Exception) {
                            ""
                        }
                        val suffix = if (detail.isBlank()) "" else ". Response: $detail"
                        throw RuntimeException(
                            "WebDAV upload failed: ${response.status.value} ${response.status.description}$suffix",
                        )
                    }
                }
                return
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                currentCoroutineContext().ensureActive()
                if (attempt == 2) throw e
                delay(1000L shl attempt)
            }
        }
    }

    suspend fun download(endpoint: WebDavEndpoint, fileName: String, readFile: suspend (ByteReadChannel) -> Unit) {
        request(endpoint, fileName, HttpMethod.Get).execute(transferClient) { response ->
            response.requireSuccess("download")
            readFile(response.bodyAsChannel())
        }
    }

    suspend fun delete(endpoint: WebDavEndpoint, fileName: String) {
        request(endpoint, fileName, HttpMethod.Delete).execute(metadataClient) { response ->
            if (response.status.value != 404) response.requireSuccess("delete")
        }
    }

    private fun propfind(endpoint: WebDavEndpoint, depth: String, properties: String) =
        request(endpoint, null, HttpMethod("PROPFIND")).apply {
            header("Depth", depth)
            contentType(ContentType.parse("application/xml; charset=utf-8"))
            setBody(
                ("<?xml version=\"1.0\" encoding=\"utf-8\" ?>" +
                    "<D:propfind xmlns:D=\"DAV:\"><D:prop>$properties</D:prop></D:propfind>").encodeToByteArray(),
            )
        }

    private fun request(endpoint: WebDavEndpoint, fileName: String?, method: HttpMethod) = HttpRequestBuilder().apply {
        url(endpoint.url(fileName))
        this.method = method
        endpoint.authorization()?.let { header("Authorization", it) }
        expectSuccess = false
    }

    private suspend fun <T> HttpRequestBuilder.execute(client: HttpClient, block: suspend (HttpResponse) -> T): T =
        client.prepareRequest(this).execute(block)

    private fun HttpResponse.requireSuccess(operation: String) {
        if (!status.isSuccess()) {
            throw RuntimeException("WebDAV $operation failed: ${status.value} ${status.description}")
        }
    }
}
