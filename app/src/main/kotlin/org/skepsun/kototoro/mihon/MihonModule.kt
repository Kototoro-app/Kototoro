package org.skepsun.kototoro.mihon

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import okhttp3.CookieJar
import okhttp3.OkHttpClient
import org.skepsun.kototoro.core.network.ContentHttpClient
import org.skepsun.kototoro.mihon.compat.KotoInjektBridge
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object MihonModule {

    @Provides
    @Singleton
    fun provideKotoInjektBridge(
        @ApplicationContext context: Context,
        @ContentHttpClient okHttpClient: OkHttpClient,
        cookieJar: CookieJar,
    ): KotoInjektBridge {
        return try {
            KotoInjektBridge(
                context = context,
                httpClient = okHttpClient,
                cookieJar = cookieJar,
            )
        } catch (e: Throwable) {
            android.util.Log.e("MihonModule", "CRITICAL ERROR: Failed to create KotoInjektBridge!", e)
            // Still need to return something or Dagger will fail. 
            // In case of fatal libs issue (NoClassDefFound), this might still crash later, 
            // but let's try to catch it here.
            throw e
        }
    }
}
