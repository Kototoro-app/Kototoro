package org.skepsun.kototoro.scrobbling.shikimori.data

import kotlinx.coroutines.runBlocking
import okhttp3.Authenticator
import okhttp3.Request
import okhttp3.Response
import okhttp3.Route
import org.skepsun.kototoro.core.network.CommonHeaders
import org.skepsun.kototoro.core.util.ext.printStackTraceDebug
import org.skepsun.kototoro.scrobbling.common.data.ScrobblerStorage
import org.skepsun.kototoro.scrobbling.common.domain.model.ScrobblerService
import org.skepsun.kototoro.scrobbling.common.domain.model.ScrobblerType
import javax.inject.Inject
import javax.inject.Provider

class ShikimoriAuthenticator @Inject constructor(
    @ScrobblerType(ScrobblerService.SHIKIMORI) private val storage: ScrobblerStorage,
    private val repositoryProvider: Provider<ShikimoriRepository>,
) : Authenticator {

    override fun authenticate(route: Route?, response: Response): Request? {
        val accessToken = storage.accessToken ?: return null
        if (!isRequestWithAccessToken(response)) {
            return null
        }
        synchronized(this) {
            val newAccessToken = storage.accessToken ?: return null
            if (accessToken != newAccessToken) {
                return newRequestWithAccessToken(response.request, newAccessToken)
            }
            val updatedAccessToken = refreshAccessToken() ?: return null
            return newRequestWithAccessToken(response.request, updatedAccessToken)
        }
    }

    private fun isRequestWithAccessToken(response: Response): Boolean {
        val header = response.request.header(CommonHeaders.AUTHORIZATION)
        return header?.startsWith("Bearer") == true
    }

    private fun newRequestWithAccessToken(request: Request, accessToken: String): Request {
        return request.newBuilder()
            .header(CommonHeaders.AUTHORIZATION, "Bearer $accessToken")
            .build()
    }

    private fun refreshAccessToken(): String? = runCatching {
        val repository = repositoryProvider.get()
        runBlocking { repository.authorize(null) }
        return storage.accessToken
    }.onFailure {
        it.printStackTraceDebug()
    }.getOrNull()
}
