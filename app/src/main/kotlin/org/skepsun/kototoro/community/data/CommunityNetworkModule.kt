package org.skepsun.kototoro.community.data

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import io.ktor.client.plugins.websocket.WebSockets
import okhttp3.OkHttpClient
import org.skepsun.kototoro.community.external.CommunityExternalSearchApi
import org.skepsun.kototoro.community.nostr.KtorNostrRelayConnector
import org.skepsun.kototoro.community.nostr.NostrRelayClient
import org.skepsun.kototoro.community.snapshot.CommunitySnapshotFetcher
import org.skepsun.kototoro.core.github.GitHubMirrorCatalogRepository
import org.skepsun.kototoro.core.network.BaseHttpClient
import org.skepsun.kototoro.core.network.createKtorOkHttpClient
import org.skepsun.kototoro.core.prefs.AppSettings
import org.skepsun.kototoro.core.prefs.GitHubMirrorCatalog
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

/** The community feature's network clients, all on the app's base client so proxy and DNS settings apply. */
@Module
@InstallIn(SingletonComponent::class)
object CommunityNetworkModule {

    /** Relays speak WebSockets; the shared Ktor factory installs no plugins, so this client adds it. */
    @Provides
    @Singleton
    fun provideNostrRelayClient(@BaseHttpClient baseClient: OkHttpClient): NostrRelayClient =
        NostrRelayClient(KtorNostrRelayConnector(createKtorOkHttpClient(baseClient).config { install(WebSockets) }))

    /** Bangumi's search timed out at 20 s on one search in nine during the evaluation (plan §3.6), so it gets longer. */
    @Provides
    @Singleton
    fun provideCommunityExternalSearchApi(@BaseHttpClient baseClient: OkHttpClient): CommunityExternalSearchApi {
        val client = baseClient.newBuilder()
            .readTimeout(30, TimeUnit.SECONDS)
            .callTimeout(45, TimeUnit.SECONDS)
            .build()
        return CommunityExternalSearchApi(createKtorOkHttpClient(client))
    }

    /** Read through the raw branch URL so the reader's GitHub mirror applies, as for every other GitHub download. */
    @Provides
    @Singleton
    fun provideCommunitySnapshotFetcher(
        @BaseHttpClient baseClient: OkHttpClient,
        settings: AppSettings,
        mirrors: GitHubMirrorCatalogRepository,
    ): CommunitySnapshotFetcher = CommunitySnapshotFetcher(
        httpClient = createKtorOkHttpClient(baseClient),
        baseUrl = CommunitySnapshotFetcher.RAW_BASE_URL,
        rewriteUrl = { url -> GitHubMirrorCatalog.apply(url, mirrors.entry(settings.gitHubMirrorId) ?: GitHubMirrorCatalog.NATIVE) },
    )
}
