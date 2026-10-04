package org.skepsun.kototoro.scrobbling.mal.data

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.network.createKtorOkHttpClient
import org.skepsun.kototoro.scrobbling.common.domain.model.ScrobblerService
import org.skepsun.kototoro.scrobbling.common.domain.model.ScrobblerType
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object MALDiscoveryNetworkModule {
    @Provides
    @Singleton
    fun provideMALDiscoveryApi(
        @ApplicationContext context: Context,
        @ScrobblerType(ScrobblerService.MAL) okHttp: OkHttpClient,
    ): MALDiscoveryApi = MALDiscoveryApi(createKtorOkHttpClient(okHttp), context.getString(R.string.mal_clientId))
}
