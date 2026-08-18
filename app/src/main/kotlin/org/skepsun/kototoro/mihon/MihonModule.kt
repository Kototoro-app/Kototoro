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
import org.skepsun.kototoro.core.prefs.AppSettings
import org.skepsun.kototoro.mihon.compat.KotoInjektBridge
import org.skepsun.kototoro.core.network.webview.WebViewClearanceSolver
import org.skepsun.kototoro.core.network.webview.WebViewExecutor
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
        webViewExecutor: dagger.Lazy<WebViewExecutor>,
        settings: AppSettings,
        clearanceSolver: WebViewClearanceSolver,
    ): KotoInjektBridge {
        return try {
            KotoInjektBridge(
                context = context,
                httpClient = okHttpClient,
                cookieJar = cookieJar,
                webViewExecutor = webViewExecutor,
                settings = settings,
                clearanceSolver = clearanceSolver,
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
