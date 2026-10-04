package org.skepsun.kototoro.backups.ui.periodical

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import io.ktor.client.HttpClient
import okhttp3.OkHttpClient
import org.skepsun.kototoro.backups.webdav.WebDavBackupClient
import org.skepsun.kototoro.core.network.BaseHttpClient
import org.skepsun.kototoro.core.network.createKtorOkHttpClient
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object WebDavNetworkModule {
    @Provides
    @Singleton
    fun provideWebDavBackupClient(@BaseHttpClient baseClient: OkHttpClient): WebDavBackupClient = WebDavBackupClient(
        createWebDavHttpClient(baseClient, 30),
        createWebDavHttpClient(baseClient, 120),
        ::parseWebDavResponse,
    )
}

internal fun createWebDavHttpClient(baseClient: OkHttpClient, callTimeoutSeconds: Long): HttpClient =
    createKtorOkHttpClient(baseClient.newBuilder().callTimeout(callTimeoutSeconds, TimeUnit.SECONDS).build())
