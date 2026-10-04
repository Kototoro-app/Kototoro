package org.skepsun.kototoro.tracking.malsync.data

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import io.ktor.client.HttpClient
import okhttp3.OkHttpClient
import org.json.JSONObject
import org.skepsun.kototoro.core.network.BaseHttpClient
import org.skepsun.kototoro.core.network.createKtorOkHttpClient
import org.skepsun.kototoro.tracking.malsync.MALSyncEntry
import org.skepsun.kototoro.tracking.malsync.MALSyncMappingApi
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object MALSyncMappingNetworkModule {
    @Provides
    @Singleton
    fun provideMALSyncMappingApi(@BaseHttpClient baseClient: OkHttpClient): MALSyncMappingApi =
        createMALSyncMappingApi(createKtorOkHttpClient(baseClient))
}

internal fun createMALSyncMappingApi(client: HttpClient): MALSyncMappingApi =
    MALSyncMappingApi(client, ::parseMALSyncEntries)

internal fun parseMALSyncEntries(text: String): List<MALSyncEntry> {
    val sites = JSONObject(text).optJSONObject("Sites") ?: return emptyList()
    return buildList {
        sites.keys().forEach { siteKey ->
            val entries = sites.optJSONObject(siteKey) ?: return@forEach
            entries.keys().forEach inner@{ idKey ->
                val entry = entries.optJSONObject(idKey) ?: return@inner
                add(
                    MALSyncEntry(
                        siteKey,
                        idKey,
                        entry.optString("identifier"),
                        entry.optString("title"),
                        entry.optString("url"),
                    ),
                )
            }
        }
    }
}
