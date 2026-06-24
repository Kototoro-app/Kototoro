package org.skepsun.kototoro.scrobbling

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.ElementsIntoSet
import okhttp3.OkHttpClient
import org.skepsun.kototoro.BuildConfig
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.db.MangaDatabase
import org.skepsun.kototoro.core.network.BaseHttpClient
import org.skepsun.kototoro.core.network.CurlLoggingInterceptor
import org.skepsun.kototoro.core.network.cookies.MutableCookieJar
import org.skepsun.kototoro.scrobbling.anilist.data.AniListAuthenticator
import org.skepsun.kototoro.scrobbling.anilist.data.AniListInterceptor
import org.skepsun.kototoro.scrobbling.anilist.domain.AniListScrobbler
import org.skepsun.kototoro.scrobbling.common.data.ScrobblerStorage
import org.skepsun.kototoro.scrobbling.common.domain.Scrobbler
import org.skepsun.kototoro.scrobbling.common.domain.model.ScrobblerService
import org.skepsun.kototoro.scrobbling.common.domain.model.ScrobblerType
import org.skepsun.kototoro.scrobbling.kitsu.data.KitsuAuthenticator
import org.skepsun.kototoro.scrobbling.kitsu.data.KitsuInterceptor
import org.skepsun.kototoro.scrobbling.kitsu.data.KitsuRepository
import org.skepsun.kototoro.scrobbling.kitsu.domain.KitsuScrobbler
import org.skepsun.kototoro.scrobbling.mal.data.MALAuthenticator
import org.skepsun.kototoro.scrobbling.mal.data.MALInterceptor
import org.skepsun.kototoro.scrobbling.mal.domain.MALScrobbler
import org.skepsun.kototoro.scrobbling.simkl.data.SimklInterceptor
import org.skepsun.kototoro.scrobbling.simkl.data.SimklRepository
import org.skepsun.kototoro.scrobbling.simkl.domain.SimklScrobbler
import org.skepsun.kototoro.scrobbling.shikimori.data.ShikimoriAuthenticator
import org.skepsun.kototoro.scrobbling.shikimori.data.ShikimoriInterceptor
import org.skepsun.kototoro.scrobbling.shikimori.domain.ShikimoriScrobbler
import org.skepsun.kototoro.scrobbling.bangumi.data.BangumiAuthenticator
import org.skepsun.kototoro.scrobbling.bangumi.data.BangumiInterceptor
import org.skepsun.kototoro.scrobbling.bangumi.domain.BangumiScrobbler
import org.skepsun.kototoro.work.domain.WorkResolver
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object ScrobblingModule {

	@Provides
	@Singleton
	@ScrobblerType(ScrobblerService.SHIKIMORI)
	fun provideShikimoriHttpClient(
		@BaseHttpClient baseHttpClient: OkHttpClient,
		authenticator: ShikimoriAuthenticator,
		@ScrobblerType(ScrobblerService.SHIKIMORI) storage: ScrobblerStorage,
	): OkHttpClient = baseHttpClient.newBuilder().apply {
		authenticator(authenticator)
		addInterceptor(ShikimoriInterceptor(storage))
	}.build()

	@Provides
	@Singleton
	@ScrobblerType(ScrobblerService.MAL)
	fun provideMALHttpClient(
		@ApplicationContext context: Context,
		@BaseHttpClient baseHttpClient: OkHttpClient,
		authenticator: MALAuthenticator,
		@ScrobblerType(ScrobblerService.MAL) storage: ScrobblerStorage,
	): OkHttpClient = baseHttpClient.newBuilder().apply {
		authenticator(authenticator)
		addInterceptor(MALInterceptor(storage, context.getString(R.string.mal_clientId)))
	}.build()

	@Provides
	@Singleton
	@ScrobblerType(ScrobblerService.ANILIST)
	fun provideAniListHttpClient(
		@BaseHttpClient baseHttpClient: OkHttpClient,
		authenticator: AniListAuthenticator,
		@ScrobblerType(ScrobblerService.ANILIST) storage: ScrobblerStorage,
	): OkHttpClient = baseHttpClient.newBuilder().apply {
		authenticator(authenticator)
		addInterceptor(AniListInterceptor(storage))
	}.build()

	@Provides
	@Singleton
	fun provideKitsuRepository(
		@ApplicationContext context: Context,
		@ScrobblerType(ScrobblerService.KITSU) storage: ScrobblerStorage,
		database: MangaDatabase,
		authenticator: KitsuAuthenticator,
		workResolver: WorkResolver,
	): KitsuRepository {
		val okHttp = OkHttpClient.Builder().apply {
			authenticator(authenticator)
			addInterceptor(KitsuInterceptor(storage))
			if (BuildConfig.DEBUG) {
				addInterceptor(CurlLoggingInterceptor())
			}
		}.build()
		return KitsuRepository(context, okHttp, storage, database, workResolver)
	}

	@Provides
	@Singleton
	@ScrobblerType(ScrobblerService.ANILIST)
	fun provideAniListStorage(
		@ApplicationContext context: Context,
	): ScrobblerStorage = ScrobblerStorage(context, ScrobblerService.ANILIST)

	@Provides
	@Singleton
	@ScrobblerType(ScrobblerService.SHIKIMORI)
	fun provideShikimoriStorage(
		@ApplicationContext context: Context,
	): ScrobblerStorage = ScrobblerStorage(context, ScrobblerService.SHIKIMORI)

	@Provides
	@Singleton
	@ScrobblerType(ScrobblerService.MAL)
	fun provideMALStorage(
		@ApplicationContext context: Context,
	): ScrobblerStorage = ScrobblerStorage(context, ScrobblerService.MAL)

	@Provides
	@Singleton
	@ScrobblerType(ScrobblerService.KITSU)
	fun provideKitsuStorage(
		@ApplicationContext context: Context,
	): ScrobblerStorage = ScrobblerStorage(context, ScrobblerService.KITSU)

	@Provides
	@Singleton
	@ScrobblerType(ScrobblerService.BANGUMI)
	fun provideBangumiStorage(
		@ApplicationContext context: Context,
	): ScrobblerStorage = ScrobblerStorage(context, ScrobblerService.BANGUMI)

	@Provides
	@Singleton
	@ScrobblerType(ScrobblerService.BANGUMI)
	fun provideBangumiHttpClient(
		authenticator: BangumiAuthenticator,
		@ScrobblerType(ScrobblerService.BANGUMI) storage: ScrobblerStorage,
	): OkHttpClient = OkHttpClient.Builder().apply {
		authenticator(authenticator)
		addInterceptor(BangumiInterceptor(storage))
		if (BuildConfig.DEBUG) {
			addInterceptor(CurlLoggingInterceptor())
		}
	}.build()

	@Provides
	@Singleton
	@ScrobblerType(ScrobblerService.MANGAUPDATES)
	fun provideMangaUpdatesStorage(
		@ApplicationContext context: Context,
	): ScrobblerStorage = ScrobblerStorage(context, ScrobblerService.MANGAUPDATES)

	@Provides
	@Singleton
	@ScrobblerType(ScrobblerService.SIMKL)
	fun provideSimklStorage(
		@ApplicationContext context: Context,
	): ScrobblerStorage = ScrobblerStorage(context, ScrobblerService.SIMKL)

	@Provides
	@Singleton
	fun provideMangaUpdatesRepository(
		@BaseHttpClient baseHttpClient: OkHttpClient,
		cookieJar: MutableCookieJar,
		@ScrobblerType(ScrobblerService.MANGAUPDATES) storage: ScrobblerStorage,
		database: MangaDatabase,
		workResolver: WorkResolver,
	): org.skepsun.kototoro.scrobbling.mangaupdates.data.MangaUpdatesRepository {
		val okHttp = baseHttpClient.newBuilder().apply {
			addInterceptor(org.skepsun.kototoro.scrobbling.mangaupdates.data.MangaUpdatesInterceptor(storage, cookieJar))
		}.build()
		return org.skepsun.kototoro.scrobbling.mangaupdates.data.MangaUpdatesRepository(
			okHttp = okHttp,
			cookieJar = cookieJar,
			storage = storage,
			db = database,
			workResolver = workResolver,
		)
	}

	@Provides
	@Singleton
	@ScrobblerType(ScrobblerService.SIMKL)
	fun provideSimklHttpClient(
		@ApplicationContext context: Context,
		@BaseHttpClient baseHttpClient: OkHttpClient,
		@ScrobblerType(ScrobblerService.SIMKL) storage: ScrobblerStorage,
	): OkHttpClient = baseHttpClient.newBuilder().apply {
		addInterceptor(
			SimklInterceptor(
				storage = storage,
				clientId = context.getString(R.string.simkl_clientId),
				appVersion = BuildConfig.VERSION_NAME,
			),
		)
	}.build()

	@Provides
	@Singleton
	fun provideSimklRepository(
		@ApplicationContext context: Context,
		@ScrobblerType(ScrobblerService.SIMKL) okHttp: OkHttpClient,
		@ScrobblerType(ScrobblerService.SIMKL) storage: ScrobblerStorage,
		database: MangaDatabase,
		workResolver: WorkResolver,
	): SimklRepository = SimklRepository(context, okHttp, storage, database, workResolver)

	@Provides
	@ElementsIntoSet
	fun provideScrobblers(
		shikimoriScrobbler: ShikimoriScrobbler,
		aniListScrobbler: AniListScrobbler,
		malScrobbler: MALScrobbler,
		kitsuScrobbler: KitsuScrobbler,
		bangumiScrobbler: BangumiScrobbler,
		mangaUpdatesScrobbler: org.skepsun.kototoro.scrobbling.mangaupdates.domain.MangaUpdatesScrobbler,
		simklScrobbler: SimklScrobbler,
	): Set<@JvmSuppressWildcards Scrobbler> = setOf(
		shikimoriScrobbler,
		aniListScrobbler,
		malScrobbler,
		kitsuScrobbler,
		bangumiScrobbler,
		mangaUpdatesScrobbler,
		simklScrobbler,
	)
}
