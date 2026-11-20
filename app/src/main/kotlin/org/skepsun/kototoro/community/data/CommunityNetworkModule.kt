package org.skepsun.kototoro.community.data

import android.content.Context
import dagger.Module
import dagger.hilt.android.qualifiers.ApplicationContext
import org.skepsun.kototoro.BuildConfig
import java.io.File
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

    /**
     * Relays speak WebSockets; the shared Ktor factory installs no plugins, so this client adds it. Relay names resolve
     * through [CommunityRelayDns], since plain DNS answers for them are poisoned in mainland China.
     */
    @Provides
    @Singleton
    fun provideNostrRelayClient(@BaseHttpClient baseClient: OkHttpClient): NostrRelayClient {
        val relayClient = baseClient.newBuilder().dns(CommunityRelayDns(baseClient, baseClient.dns)).build()
        return NostrRelayClient(KtorNostrRelayConnector(createKtorOkHttpClient(relayClient).config { install(WebSockets) }))
    }

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
        @ApplicationContext context: Context,
        @BaseHttpClient baseClient: OkHttpClient,
        settings: AppSettings,
        mirrors: GitHubMirrorCatalogRepository,
    ): CommunitySnapshotFetcher {
        // Debug builds only: a base URL in this file replaces the published index, so the community screens can be
        // checked against a seeded snapshot (`run-as … tee files/community/debug-snapshot-base-url`, then restart).
        val override = if (BuildConfig.DEBUG) {
            File(context.filesDir, "community/debug-snapshot-base-url").takeIf { it.isFile }?.readText()?.trim()?.ifEmpty { null }
        } else {
            null
        }
        return CommunitySnapshotFetcher(
            httpClient = createKtorOkHttpClient(baseClient),
            baseUrl = override ?: CommunitySnapshotFetcher.RAW_BASE_URL,
            rewriteUrl = if (override != null) {
                { it }
            } else {
                { url -> GitHubMirrorCatalog.apply(url, mirrors.entry(settings.gitHubMirrorId) ?: GitHubMirrorCatalog.NATIVE) }
            },
        )
    }
}
