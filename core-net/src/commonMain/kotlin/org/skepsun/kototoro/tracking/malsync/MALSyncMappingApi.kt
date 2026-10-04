package org.skepsun.kototoro.tracking.malsync

import io.ktor.client.HttpClient
import io.ktor.client.plugins.expectSuccess
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Public cross-site mapping protocol. The platform owns JSON projection and the client's lifetime. */
class MALSyncMappingApi(
    private val httpClient: HttpClient,
    private val parseEntries: (String) -> List<MALSyncEntry>,
) {
    suspend fun resolve(source: MALSyncService, remoteId: Long, kind: MALSyncKind): List<MALSyncMapping> {
        val path = source.apiPath ?: return emptyList()
        currentCoroutineContext().ensureActive()
        return httpClient.prepareGet("$BASE_URL/$path/${kind.slug}/$remoteId") {
            expectSuccess = false
        }.execute { response ->
            if (!response.status.isSuccess()) emptyList() else {
                MALSyncMappingRules.map(parseEntries(response.bodyAsText()), source)
            }
        }
    }

    private companion object {
        const val BASE_URL = "https://api.malsync.moe"
    }
}
