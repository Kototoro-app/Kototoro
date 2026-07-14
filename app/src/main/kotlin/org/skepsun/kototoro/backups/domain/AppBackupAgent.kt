package org.skepsun.kototoro.backups.domain

import android.app.backup.BackupAgent
import android.app.backup.BackupDataInput
import android.app.backup.BackupDataOutput
import android.app.backup.FullBackupDataOutput
import android.content.Context
import android.os.ParcelFileDescriptor
import androidx.annotation.VisibleForTesting
import com.google.common.io.ByteStreams
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.skepsun.kototoro.backups.data.BackupRepository
import org.skepsun.kototoro.core.BaseApp
import org.skepsun.kototoro.core.db.MangaDatabase
import org.skepsun.kototoro.core.prefs.AppSettings
import org.skepsun.kototoro.explore.data.ContentSourcesRepository
import org.skepsun.kototoro.explore.data.SourceAvailabilityRepository
import org.skepsun.kototoro.filter.data.SavedFiltersRepository
import org.skepsun.kototoro.reader.data.TapGridSettings
import java.io.File
import java.io.FileDescriptor
import java.io.FileInputStream
import java.util.EnumSet
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

class AppBackupAgent : BackupAgent() {

	override fun onBackup(
		oldState: ParcelFileDescriptor?,
		data: BackupDataOutput?,
		newState: ParcelFileDescriptor?
	) = Unit

	override fun onRestore(
		data: BackupDataInput?,
		appVersionCode: Int,
		newState: ParcelFileDescriptor?
	) = Unit

	override fun onFullBackup(data: FullBackupDataOutput) {
		super.onFullBackup(data)
		val db = MangaDatabase(context = applicationContext)
		val appSettings = AppSettings(applicationContext)
		val entryPoint = EntryPointAccessors.fromApplication(
			applicationContext,
			BaseApp.BaseAppEntryPoint::class.java,
		)
		val jsonSourceManager = org.skepsun.kototoro.core.jsonsource.JsonSourceManager(
			jsonSourceDao = db.getJsonSourceDao(),
			appSettings = appSettings,
		)
		val sourceTypeIdentifier = org.skepsun.kototoro.core.jsonsource.SourceTypeIdentifier()
		val json = Json { ignoreUnknownKeys = true }
		val sourceGroupManager = org.skepsun.kototoro.core.jsonsource.SourceGroupManager(
			sourceTypeIdentifier = sourceTypeIdentifier,
			jsonSourceManager = jsonSourceManager,
			json = json
		)
		val file = createBackupFile(
			this,
			BackupRepository(
				appContext = applicationContext,
				database = db,
				settings = appSettings,
				tapGridSettings = TapGridSettings(applicationContext),
				mangaSourcesRepository = ContentSourcesRepository(
					context = applicationContext,
					db = db,
					settings = appSettings,
					jsonSourceManager = jsonSourceManager,
					sourceTypeIdentifier = sourceTypeIdentifier,
					sourceGroupManager = sourceGroupManager,
					mihonExtensionManager = org.skepsun.kototoro.mihon.MihonExtensionManager(
						context = applicationContext,
						loader = org.skepsun.kototoro.mihon.MihonExtensionLoader(
							applicationContext = applicationContext,
							injektBridge = dagger.Lazy {
								org.skepsun.kototoro.mihon.compat.KotoInjektBridge(
									context = applicationContext,
									httpClient = okhttp3.OkHttpClient(),
									cookieJar = org.skepsun.kototoro.core.network.cookies.AndroidCookieJar(),
								)
							},
							settings = appSettings,
						),
					),
					aniyomiExtensionManager = org.skepsun.kototoro.aniyomi.AniyomiExtensionManager(
						context = applicationContext,
						loader = org.skepsun.kototoro.aniyomi.AniyomiExtensionLoader(
							applicationContext = applicationContext,
							injektBridge = dagger.Lazy {
								org.skepsun.kototoro.aniyomi.compat.KotoAniyomiInjektBridge(
									mihonInjektBridge = org.skepsun.kototoro.mihon.compat.KotoInjektBridge(
										context = applicationContext,
										httpClient = okhttp3.OkHttpClient(),
										cookieJar = org.skepsun.kototoro.core.network.cookies.AndroidCookieJar(),
									)
								)
							},
							settings = appSettings,
						)
					),
					ireaderExtensionManager = org.skepsun.kototoro.ireader.IReaderExtensionManager(
						context = applicationContext,
						loader = org.skepsun.kototoro.ireader.IReaderExtensionLoader(
							applicationContext = applicationContext,
							httpClient = okhttp3.OkHttpClient(),
							settings = appSettings,
						),
					),
					cloudstreamRuntimeManager = org.skepsun.kototoro.cloudstream.runtime.CloudstreamRuntimeManager(
						context = applicationContext,
						contentHttpClient = okhttp3.OkHttpClient(),
					),
					sourceAvailabilityRepository = SourceAvailabilityRepository(appSettings),
					projectionContentTypeBackfill = org.skepsun.kototoro.space.data.ProjectionContentTypeBackfill(db),
				),
				savedFiltersRepository = SavedFiltersRepository(
					context = applicationContext,
				),
				workResolver = entryPoint.workResolver(),
			),
		)
		try {
			fullBackupFile(file, data)
		} finally {
			file.delete()
		}
	}

	override fun onRestoreFile(
		data: ParcelFileDescriptor,
		size: Long,
		destination: File?,
		type: Int,
		mode: Long,
		mtime: Long
	) {
		if (destination?.name?.endsWith(".bk.zip") == true) {
			val db = MangaDatabase(applicationContext)
			val appSettings = AppSettings(applicationContext)
			val entryPoint = EntryPointAccessors.fromApplication(
				applicationContext,
				BaseApp.BaseAppEntryPoint::class.java,
			)
			val jsonSourceManager = org.skepsun.kototoro.core.jsonsource.JsonSourceManager(
				jsonSourceDao = db.getJsonSourceDao(),
				appSettings = appSettings,
			)
			val sourceTypeIdentifier = org.skepsun.kototoro.core.jsonsource.SourceTypeIdentifier()
			val json = Json { ignoreUnknownKeys = true }
			val sourceGroupManager = org.skepsun.kototoro.core.jsonsource.SourceGroupManager(
				sourceTypeIdentifier = sourceTypeIdentifier,
				jsonSourceManager = jsonSourceManager,
				json = json
			)
			restoreBackupFile(
				data.fileDescriptor,
				size,
				BackupRepository(
					appContext = applicationContext,
					database = db,
					settings = appSettings,
					tapGridSettings = TapGridSettings(applicationContext),
					mangaSourcesRepository = ContentSourcesRepository(
						context = applicationContext,
						db = db,
						settings = appSettings,
						jsonSourceManager = jsonSourceManager,
						sourceTypeIdentifier = sourceTypeIdentifier,
						sourceGroupManager = sourceGroupManager,
						mihonExtensionManager = org.skepsun.kototoro.mihon.MihonExtensionManager(
							context = applicationContext,
							loader = org.skepsun.kototoro.mihon.MihonExtensionLoader(
								applicationContext = applicationContext,
								injektBridge = dagger.Lazy {
									org.skepsun.kototoro.mihon.compat.KotoInjektBridge(
										context = applicationContext,
										httpClient = okhttp3.OkHttpClient(),
										cookieJar = org.skepsun.kototoro.core.network.cookies.AndroidCookieJar(),
									)
								},
								settings = appSettings,
							),
						),
						aniyomiExtensionManager = org.skepsun.kototoro.aniyomi.AniyomiExtensionManager(
							context = applicationContext,
							loader = org.skepsun.kototoro.aniyomi.AniyomiExtensionLoader(
								applicationContext = applicationContext,
								injektBridge = dagger.Lazy {
									org.skepsun.kototoro.aniyomi.compat.KotoAniyomiInjektBridge(
										mihonInjektBridge = org.skepsun.kototoro.mihon.compat.KotoInjektBridge(
											context = applicationContext,
											httpClient = okhttp3.OkHttpClient(),
											cookieJar = org.skepsun.kototoro.core.network.cookies.AndroidCookieJar(),
										)
									)
								},
								settings = appSettings,
							)
						),
						ireaderExtensionManager = org.skepsun.kototoro.ireader.IReaderExtensionManager(
							context = applicationContext,
							loader = org.skepsun.kototoro.ireader.IReaderExtensionLoader(
								applicationContext = applicationContext,
								httpClient = okhttp3.OkHttpClient(),
								settings = appSettings,
							),
						),
						cloudstreamRuntimeManager = org.skepsun.kototoro.cloudstream.runtime.CloudstreamRuntimeManager(
							context = applicationContext,
							contentHttpClient = okhttp3.OkHttpClient(),
						),
						sourceAvailabilityRepository = SourceAvailabilityRepository(appSettings),
						projectionContentTypeBackfill = org.skepsun.kototoro.space.data.ProjectionContentTypeBackfill(db),
					),
					savedFiltersRepository = SavedFiltersRepository(
						context = applicationContext,
					),
					workResolver = entryPoint.workResolver(),
				),
			)
			destination.delete()
		} else {
			super.onRestoreFile(data, size, destination, type, mode, mtime)
		}
	}

	@VisibleForTesting
	fun createBackupFile(context: Context, repository: BackupRepository): File {
		val file = BackupUtils.createTempFile(context)
		ZipOutputStream(file.outputStream()).use { output ->
			runBlocking {
				repository.createBackup(output, null)
			}
		}
		return file
	}

	@VisibleForTesting
	fun restoreBackupFile(fd: FileDescriptor, size: Long, repository: BackupRepository) {
		ZipInputStream(ByteStreams.limit(FileInputStream(fd), size)).use { input ->
			val sections = EnumSet.allOf(BackupSection::class.java)
			// managed externally
			sections.remove(BackupSection.SETTINGS)
			sections.remove(BackupSection.SETTINGS_READER_GRID)
			runBlocking {
				repository.restoreBackup(input, sections, null)
			}
		}
	}
}
