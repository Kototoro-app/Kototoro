package org.skepsun.kototoro.sync.google.data

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import io.ktor.client.HttpClient
import okhttp3.OkHttpClient
import org.skepsun.kototoro.core.network.createKtorOkHttpClient
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object GoogleDriveSyncNetworkModule {

    @Provides
    @Singleton
    fun provideGoogleDriveSyncApi(): GoogleDriveSyncApi = GoogleDriveSyncApi(createGoogleDriveSyncHttpClient())
}

internal fun createGoogleDriveSyncHttpClient(
    okHttpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .callTimeout(60, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build(),
): HttpClient = createKtorOkHttpClient(okHttpClient)
