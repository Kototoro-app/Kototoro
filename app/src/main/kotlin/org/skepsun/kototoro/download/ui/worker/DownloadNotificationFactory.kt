package org.skepsun.kototoro.download.ui.worker

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Drawable
import android.text.format.DateUtils
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.PendingIntentCompat
import androidx.core.graphics.drawable.toBitmap
import androidx.work.WorkManager
import coil3.ImageLoader
import coil3.request.ImageRequest
import coil3.request.allowHardware
import coil3.size.Scale
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.ErrorReporterReceiver
import org.skepsun.kototoro.core.LocalizedAppContext
import org.skepsun.kototoro.core.model.LocalMangaSource
import org.skepsun.kototoro.core.model.isNsfw
import org.skepsun.kototoro.core.nav.AppRouter
import org.skepsun.kototoro.core.parser.ContentDataRepository
import org.skepsun.kototoro.core.util.ext.getDrawableOrThrow
import org.skepsun.kototoro.core.util.ext.getNotificationIconSize
import org.skepsun.kototoro.core.util.ext.isReportable
import org.skepsun.kototoro.core.util.ext.mangaSourceExtra
import org.skepsun.kototoro.core.util.ext.printStackTraceDebug
import org.skepsun.kototoro.details.ui.model.DetailsOrigin
import org.skepsun.kototoro.download.domain.DownloadState
import org.skepsun.kototoro.download.ui.list.DownloadsActivity
import org.skepsun.kototoro.parsers.model.Content
import org.skepsun.kototoro.parsers.util.format
import org.skepsun.kototoro.parsers.util.runCatchingCancellable
import org.skepsun.kototoro.work.domain.WorkResolver
import java.util.UUID
import androidx.appcompat.R as appcompatR

private const val CHANNEL_ID_DEFAULT = "download"
private const val CHANNEL_ID_SILENT = "download_bg"
private const val GROUP_ID = "downloads"

class DownloadNotificationFactory @AssistedInject constructor(
	@LocalizedAppContext private val context: Context,
	private val workManager: WorkManager,
	private val coil: ImageLoader,
	private val contentDataRepository: ContentDataRepository,
	private val workResolver: WorkResolver,
	@Assisted private val uuid: UUID,
	@Assisted val isSilent: Boolean,
) {

	private val covers = HashMap<Content, Drawable>() // TODO cache
	private val builder = NotificationCompat.Builder(context, if (isSilent) CHANNEL_ID_SILENT else CHANNEL_ID_DEFAULT)
	private val mutex = Mutex()

	private val queueIntent = PendingIntentCompat.getActivity(
		context,
		0,
		Intent(context, DownloadsActivity::class.java),
		0,
		false,
	)

	private val actionCancel by lazy {
		NotificationCompat.Action(
			appcompatR.drawable.abc_ic_clear_material,
			context.getString(android.R.string.cancel),
			workManager.createCancelPendingIntent(uuid),
		)
	}

	private val actionPause by lazy {
		NotificationCompat.Action(
			R.drawable.ic_action_pause,
			context.getString(R.string.pause),
			PausingReceiver.createPausePendingIntent(context, uuid),
		)
	}

	private val actionResume by lazy {
		NotificationCompat.Action(
			R.drawable.ic_action_resume,
			context.getString(R.string.resume),
			PausingReceiver.createResumePendingIntent(context, uuid),
		)
	}

	private val actionRetry by lazy {
		NotificationCompat.Action(
			R.drawable.ic_retry,
			context.getString(R.string.retry),
			actionResume.actionIntent,
		)
	}

	private val actionSkip by lazy {
		NotificationCompat.Action(
			R.drawable.ic_action_skip,
			context.getString(R.string.skip),
			PausingReceiver.createSkipPendingIntent(context, uuid),
		)
	}

	init {
		createChannels()
		builder.setOnlyAlertOnce(true)
		builder.setDefaults(0)
		builder.foregroundServiceBehavior = if (isSilent) {
			NotificationCompat.FOREGROUND_SERVICE_DEFERRED
		} else {
			NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE
		}
		builder.setSilent(true)
		builder.setGroup(GROUP_ID)
		builder.setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_CHILDREN)
		builder.priority = if (isSilent) NotificationCompat.PRIORITY_MIN else NotificationCompat.PRIORITY_DEFAULT
	}

	suspend fun create(state: DownloadState?): Notification = mutex.withLock {
		val displayContent = resolveDisplayContent(state)
		val titleContent = displayContent ?: state?.manga
		if (state == null) {
			builder.setContentTitle(context.getString(R.string.manga_downloading_))
			builder.setContentText(context.getString(R.string.preparing_))
		} else {
			builder.setContentTitle(titleContent?.title ?: state.manga.title)
			builder.setContentText(context.getString(R.string.manga_downloading_))
		}
		builder.setProgress(1, 0, true)
		builder.setSmallIcon(android.R.drawable.stat_sys_download)
		builder.setContentIntent(queueIntent)
		builder.setStyle(null)
		builder.setLargeIcon(if (titleContent != null) getCover(titleContent)?.toBitmap() else null)
		builder.clearActions()
		builder.setSubText(null)
		builder.setShowWhen(false)
		builder.setVisibility(
			if (titleContent?.isNsfw() == true || state?.manga?.isNsfw() == true) {
				NotificationCompat.VISIBILITY_SECRET
			} else {
				NotificationCompat.VISIBILITY_PRIVATE
			},
		)
		when {
			state == null -> Unit
			state.isCompleted || state.localContent != null -> {
				builder.setProgress(0, 0, false)
				builder.setSubText(context.getString(state.taskKind.actionTitleResId))
				builder.setContentText(context.getString(state.taskKind.completedStatusResId))
				builder.setContentIntent(
					createContentIntent(
						displayManga = displayContent,
						localManga = state.localContent?.manga,
						executionManga = state.manga,
					),
				)
				builder.setAutoCancel(true)
				builder.setSmallIcon(android.R.drawable.stat_sys_download_done)
				builder.setCategory(null)
				builder.setStyle(null)
				builder.setOngoing(false)
				builder.setShowWhen(true)
				builder.setWhen(System.currentTimeMillis())
			}

			state.isStopped -> {
				builder.setProgress(0, 0, false)
				builder.setSubText(context.getString(state.taskKind.actionTitleResId))
				builder.setContentText(context.getString(R.string.queued))
				builder.setCategory(NotificationCompat.CATEGORY_PROGRESS)
				builder.setStyle(null)
				builder.setOngoing(true)
				builder.setSmallIcon(R.drawable.ic_stat_paused)
				builder.addAction(actionCancel)
			}

			state.isPaused -> { // paused (with error or manually)
				builder.setSubText(context.getString(state.taskKind.actionTitleResId))
				builder.setProgress(state.max, state.progress, false)
				val percent = if (state.percent >= 0) {
					context.getString(R.string.percent_string_pattern, (state.percent * 100).format())
				} else {
					null
				}
				if (state.errorMessage != null) {
					builder.setContentText(
						context.getString(
							R.string.download_summary_pattern,
							percent,
							state.errorMessage,
						),
					)
				} else {
					builder.setContentText(percent)
				}
				builder.setCategory(NotificationCompat.CATEGORY_PROGRESS)
				builder.setStyle(null)
				builder.setOngoing(true)
				builder.setSmallIcon(R.drawable.ic_stat_paused)
				builder.addAction(actionCancel)
				if (state.errorMessage != null) {
					builder.addAction(actionRetry)
					builder.addAction(actionSkip)
				} else {
					builder.addAction(actionResume)
				}
			}

			state.error != null -> { // error, final state
				builder.setProgress(0, 0, false)
				builder.setSmallIcon(android.R.drawable.stat_notify_error)
				builder.setSubText(context.getString(R.string.error))
				builder.setContentText(state.errorMessage)
				builder.setAutoCancel(true)
				builder.setOngoing(false)
				builder.setCategory(NotificationCompat.CATEGORY_ERROR)
				builder.setShowWhen(true)
				builder.setWhen(System.currentTimeMillis())
				builder.setStyle(NotificationCompat.BigTextStyle().bigText(state.errorMessage))
				if (state.error.isReportable()) {
					ErrorReporterReceiver.getPendingIntent(context, state.error)?.let { reportIntent ->
						builder.addAction(
							NotificationCompat.Action(
								0,
								context.getString(R.string.report),
								reportIntent,
							),
						)
					}
				}
			}

			else -> {
				builder.setSubText(context.getString(state.taskKind.activeStatusResId))
				builder.setProgress(state.max, state.progress, false)
				builder.setContentText(getProgressString(state.percent, state.eta, state.isStuck))
				builder.setCategory(NotificationCompat.CATEGORY_PROGRESS)
				builder.setStyle(null)
				builder.setOngoing(true)
				builder.addAction(actionCancel)
				builder.addAction(actionPause)
			}
		}
		return builder.build()
	}

	private suspend fun resolveDisplayContent(state: DownloadState?): Content? {
		val displayMangaId = state?.displayMangaId ?: return null
		return contentDataRepository.findDisplayContentById(displayMangaId, withChapters = false)
	}

	private fun getProgressString(percent: Float, eta: Long, isStuck: Boolean): CharSequence? {
		val percentString = if (percent >= 0f) {
			context.getString(R.string.percent_string_pattern, (percent * 100).format())
		} else {
			null
		}
		val etaString = when {
			eta <= 0L -> null
			isStuck -> context.getString(R.string.stuck)
			else -> DateUtils.getRelativeTimeSpanString(
				eta,
				System.currentTimeMillis(),
				DateUtils.SECOND_IN_MILLIS,
			)
		}
		return when {
			percentString == null && etaString == null -> null
			percentString != null && etaString == null -> percentString
			percentString == null && etaString != null -> etaString
			else -> context.getString(R.string.download_summary_pattern, percentString, etaString)
		}
	}

	private suspend fun createContentIntent(
		displayManga: Content?,
		localManga: Content?,
		executionManga: Content?,
	) = PendingIntentCompat.getActivity(
		context,
		(displayManga ?: localManga ?: executionManga).hashCode(),
		when {
			displayManga != null -> resolveDetailsIntent(displayManga)
			localManga != null -> resolveDetailsIntent(localManga)
			executionManga != null -> resolveDetailsIntent(executionManga)
			else -> AppRouter.listIntent(context, LocalMangaSource, null, null)
		},
		PendingIntent.FLAG_CANCEL_CURRENT,
		false,
	)

	private suspend fun resolveDetailsIntent(content: Content): Intent {
		val entityId = workResolver.resolveByMangaId(content.id).entityId
		val origin = if (entityId != null) {
			DetailsOrigin.EntityGraph(
				entityId = entityId,
				initialProjectionLocalMangaId = content.id,
			)
		} else {
			DetailsOrigin.LocalMangaContent(
				org.skepsun.kototoro.core.model.parcelable.ParcelableContent(content),
			)
		}
		return AppRouter.detailsIntent(context, origin)
	}

	private suspend fun getCover(manga: Content) = covers[manga] ?: run {
		runCatchingCancellable {
			coil.execute(
				ImageRequest.Builder(context)
					.data(manga.coverUrl)
					.allowHardware(false)
					.mangaSourceExtra(manga.source)
					.size(context.resources.getNotificationIconSize())
					.scale(Scale.FILL)
					.build(),
			).getDrawableOrThrow()
		}.onSuccess {
			covers[manga] = it
		}.onFailure {
			it.printStackTraceDebug()
		}.getOrNull()
	}

	private fun createChannels() {
		val manager = NotificationManagerCompat.from(context)
		manager.createNotificationChannel(
			NotificationChannelCompat.Builder(CHANNEL_ID_DEFAULT, NotificationManagerCompat.IMPORTANCE_LOW)
				.setName(context.getString(R.string.downloads))
				.setVibrationEnabled(false)
				.setLightsEnabled(false)
				.setSound(null, null)
				.build(),
		)
		manager.createNotificationChannel(
			NotificationChannelCompat.Builder(CHANNEL_ID_SILENT, NotificationManagerCompat.IMPORTANCE_MIN)
				.setName(context.getString(R.string.downloads_background))
				.setVibrationEnabled(false)
				.setLightsEnabled(false)
				.setSound(null, null)
				.setShowBadge(false)
				.build(),
		)
	}

	@AssistedFactory
	interface Factory {

		fun create(uuid: UUID, isSilent: Boolean): DownloadNotificationFactory
	}
}
