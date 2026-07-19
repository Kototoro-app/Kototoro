package org.skepsun.kototoro.core

import android.app.Application.ActivityLifecycleCallbacks
import android.content.Context
import android.os.Build
import androidx.annotation.WorkerThread
import androidx.appcompat.app.AppCompatDelegate
import androidx.hilt.work.HiltWorkerFactory
import androidx.room.InvalidationTracker
import androidx.work.Configuration
import dagger.hilt.EntryPoint
import coil3.ImageLoader
import coil3.SingletonImageLoader
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttp
import org.acra.ACRA
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import org.acra.ReportField
import org.acra.config.dialog
import org.acra.config.httpSender
import org.acra.data.StringFormat
import kotlinx.coroutines.withContext
import org.acra.ktx.initAcra
import org.acra.sender.HttpSender
import org.conscrypt.Conscrypt
import org.skepsun.kototoro.BuildConfig
import org.skepsun.kototoro.R
import com.github.tvbox.osc.base.App
import org.skepsun.kototoro.aniyomi.AniyomiExtensionManager
import org.skepsun.kototoro.core.db.MangaDatabase
import org.skepsun.kototoro.core.os.AppValidator
import org.skepsun.kototoro.core.os.RomCompat
import org.skepsun.kototoro.core.prefs.AppSettings
import org.skepsun.kototoro.core.util.ext.processLifecycleScope
import org.skepsun.kototoro.local.data.LocalStorageChanges
import org.skepsun.kototoro.local.data.index.LocalContentIndex
import org.skepsun.kototoro.local.domain.model.LocalContent
import org.skepsun.kototoro.mihon.MihonExtensionManager
import org.skepsun.kototoro.ireader.IReaderExtensionManager
import org.skepsun.kototoro.parsers.util.suspendlazy.getOrNull
import org.skepsun.kototoro.settings.work.WorkScheduleManager
import java.security.Security
import javax.inject.Provider

open class BaseApp : App(), Configuration.Provider, SingletonImageLoader.Factory {

	private val entryPoint: BaseAppEntryPoint by lazy(LazyThreadSafetyMode.NONE) {
		EntryPointAccessors.fromApplication(this, BaseAppEntryPoint::class.java)
	}

	override val workManagerConfiguration: Configuration
		get() = Configuration.Builder()
			.setWorkerFactory(entryPoint.workerFactory())
			.build()

	override fun newImageLoader(context: Context): ImageLoader {
		return entryPoint.imageLoader()
	}

	override fun onCreate() {
		super.onCreate()
		try {
			OkHttp.initialize(this)
		} catch (e: Throwable) {
			// Ignore initialization errors
		}
		if (ACRA.isACRASenderServiceProcess()) {
			return
		}
		entryPoint.settings().reconcileAfterAppUpgrade(BuildConfig.VERSION_CODE)
		AppCompatDelegate.setDefaultNightMode(entryPoint.settings().theme)
		// TLS 1.3 support for Android < 10
		if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
			try {
				Security.insertProviderAt(Conscrypt.newProvider(), 1)
			} catch (e: Throwable) {
				// Ignore
			}
		}
		setupActivityLifecycleCallbacks()
		processLifecycleScope.launch {
			runCatching {
				ACRA.errorReporter.putCustomData("isOriginalApp", entryPoint.appValidator().isOriginalApp.getOrNull().toString())
				ACRA.errorReporter.putCustomData("isMiui", RomCompat.isMiui.getOrNull().toString())
			}
		}
		if (!entryPoint.settings().isEntityGraphMigrated ||
			!entryPoint.settings().isLegacyFavouriteProjectionMigrationCompleted
		) {
			val request = OneTimeWorkRequestBuilder<org.skepsun.kototoro.entitygraph.work.EntityGraphMigrationWorker>().build()
			WorkManager.getInstance(this).enqueue(request)
			entryPoint.settings().isEntityGraphMigrated = true
		}
		processLifecycleScope.launch(Dispatchers.Default) {
			runCatching {
				if (entryPoint.settings().requiresWorkMigrationNormalization) {
					entryPoint.favouritesRepository().normalizeWorkFavouritesIfNeeded()
					entryPoint.historyRepository().normalizeWorkHistoryIfNeeded()
					entryPoint.settings().requiresWorkMigrationNormalization = false
				}
				setupDatabaseObservers()
				entryPoint.localStorageChanges().collect(entryPoint.localContentIndexProvider().get())
			}
		}
		try {
			entryPoint.workScheduleManager().init()
		} catch (e: Throwable) {
			e.printStackTrace()
		}
		try {
			entryPoint.mihonExtensionManager().initialize()
		} catch (e: Throwable) {
			e.printStackTrace()
		}
		try {
			entryPoint.aniyomiExtensionManager().initialize()
		} catch (e: Throwable) {
			e.printStackTrace()
		}
		try {
			entryPoint.ireaderExtensionManager().initialize()
		} catch (e: Throwable) {
			e.printStackTrace()
		}
		processLifecycleScope.launch(Dispatchers.IO) {
			try {
				BaseAppHolder.setCloudstreamRuntimeManager(entryPoint.cloudstreamRuntimeManager())
				entryPoint.cloudstreamRuntimeManager().initialize()
			} catch (e: Throwable) {
				e.printStackTrace()
			}
			try {
				org.skepsun.kototoro.core.extensions.GlobalExtensionManager.initialize(this@BaseApp)
			} catch (e: Throwable) {
				e.printStackTrace()
			}
		}
	}

	override fun attachBaseContext(base: Context) {
		super.attachBaseContext(base)
		if (ACRA.isACRASenderServiceProcess()) {
			return
		}
		initAcra {
			buildConfigClass = BuildConfig::class.java
			reportFormat = StringFormat.JSON
			httpSender {
				uri = getString(R.string.url_error_report)
				basicAuthLogin = getString(R.string.acra_login)
				basicAuthPassword = getString(R.string.acra_password)
				httpMethod = HttpSender.Method.POST
			}
			reportContent = listOf(
				ReportField.PACKAGE_NAME,
				ReportField.INSTALLATION_ID,
				ReportField.APP_VERSION_CODE,
				ReportField.APP_VERSION_NAME,
				ReportField.ANDROID_VERSION,
				ReportField.PHONE_MODEL,
				ReportField.STACK_TRACE,
				ReportField.CRASH_CONFIGURATION,
				ReportField.CUSTOM_DATA,
			)

			dialog {
				text = getString(R.string.crash_text)
				title = getString(R.string.error_occurred)
				positiveButtonText = getString(R.string.send)
				resIcon = R.drawable.ic_alert_outline
				resTheme = android.R.style.Theme_Material_Light_Dialog_Alert
			}
		}
		org.skepsun.kototoro.core.logs.CrashLogWriter.install(this)
	}

	@WorkerThread
	private fun setupDatabaseObservers() {
		val tracker = entryPoint.database().get().invalidationTracker
		entryPoint.databaseObserversProvider().get().forEach {
			tracker.addObserver(it)
		}
	}

	private fun setupActivityLifecycleCallbacks() {
		entryPoint.activityLifecycleCallbacks().forEach {
			registerActivityLifecycleCallbacks(it)
		}
	}

	@EntryPoint
	@InstallIn(SingletonComponent::class)
	interface BaseAppEntryPoint {
		fun databaseObserversProvider(): Provider<Set<@JvmSuppressWildcards InvalidationTracker.Observer>>
		fun activityLifecycleCallbacks(): Set<@JvmSuppressWildcards ActivityLifecycleCallbacks>
		fun database(): Provider<MangaDatabase>
		fun settings(): AppSettings
		fun workerFactory(): HiltWorkerFactory
		fun appValidator(): AppValidator
		fun workScheduleManager(): WorkScheduleManager
		fun localContentIndexProvider(): Provider<LocalContentIndex>
		@LocalStorageChanges
		fun localStorageChanges(): MutableSharedFlow<LocalContent?>
		fun mihonExtensionManager(): MihonExtensionManager
		fun aniyomiExtensionManager(): AniyomiExtensionManager
		fun ireaderExtensionManager(): IReaderExtensionManager
		fun cloudstreamRuntimeManager(): org.skepsun.kototoro.cloudstream.runtime.CloudstreamRuntimeManager
		fun jsonSourceManager(): org.skepsun.kototoro.core.jsonsource.JsonSourceManager
		fun externalExtensionRepoRepository(): org.skepsun.kototoro.extensions.repo.ExternalExtensionRepoRepository
		fun extensionInstallService(): org.skepsun.kototoro.extensions.install.ExtensionInstallService
		fun contentSourcesRepository(): org.skepsun.kototoro.explore.data.ContentSourcesRepository
		fun favouritesRepository(): org.skepsun.kototoro.favourites.domain.FavouritesRepository
		fun historyRepository(): org.skepsun.kototoro.history.data.HistoryRepository
		fun workResolver(): org.skepsun.kototoro.work.domain.WorkResolver
		fun imageLoader(): ImageLoader
	}
}
