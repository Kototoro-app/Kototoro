package org.skepsun.kototoro.tracker.work

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationCompat.VISIBILITY_PRIVATE
import androidx.core.app.NotificationCompat.VISIBILITY_SECRET
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.PendingIntentCompat
import androidx.core.content.ContextCompat
import coil3.ImageLoader
import coil3.request.ImageRequest
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.LocalizedAppContext
import org.skepsun.kototoro.core.model.getLocalizedTitle
import org.skepsun.kototoro.core.model.isNsfw
import org.skepsun.kototoro.core.model.parcelable.ParcelableContent
import org.skepsun.kototoro.core.nav.AppRouter
import org.skepsun.kototoro.core.parser.ContentDataRepository
import org.skepsun.kototoro.core.prefs.AppSettings
import org.skepsun.kototoro.core.util.ext.checkNotificationPermission
import org.skepsun.kototoro.core.util.ext.getQuantityStringSafe
import org.skepsun.kototoro.core.util.ext.mangaSourceExtra
import org.skepsun.kototoro.core.util.ext.toBitmapOrNull
import org.skepsun.kototoro.details.ui.model.DetailsOrigin
import org.skepsun.kototoro.parsers.model.Content
import org.skepsun.kototoro.parsers.model.ContentChapter
import org.skepsun.kototoro.work.domain.WorkResolver
import javax.inject.Inject

class TrackerNotificationHelper @Inject constructor(
	@LocalizedAppContext private val applicationContext: Context,
	private val settings: AppSettings,
	private val coil: ImageLoader,
	private val contentDataRepository: ContentDataRepository,
	private val workResolver: WorkResolver,
) {

	fun getAreNotificationsEnabled(): Boolean {
		val manager = NotificationManagerCompat.from(applicationContext)
		if (!manager.areNotificationsEnabled()) {
			return false
		}
		return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
			val channel = manager.getNotificationChannel(CHANNEL_ID)
			channel != null && channel.importance != NotificationManager.IMPORTANCE_NONE
		} else {
			// fallback
			settings.isTrackerNotificationsEnabled
		}
	}

	suspend fun createNotification(manga: Content, newChapters: List<ContentChapter>): NotificationInfo? {
		if (newChapters.isEmpty() || !applicationContext.checkNotificationPermission(CHANNEL_ID)) {
			return null
		}
		val representativeManga = contentDataRepository.findPreferredLocalContentById(
			manga.id,
			withChapters = false,
		) ?: manga
		if (representativeManga.isNsfw() && (settings.isTrackerNsfwDisabled || settings.isNsfwContentDisabled)) {
			return null
		}
		val id = representativeManga.url.hashCode()
		val builder = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
		val summary = applicationContext.resources.getQuantityStringSafe(
			R.plurals.new_chapters,
			newChapters.size,
			newChapters.size,
		)
		with(builder) {
			setContentText(summary)
			setContentTitle(representativeManga.title)
			setNumber(newChapters.size)
			setLargeIcon(
				coil.execute(
					ImageRequest.Builder(applicationContext)
						.data(representativeManga.coverUrl)
						.mangaSourceExtra(representativeManga.source)
						.build(),
				).toBitmapOrNull(),
			)
			setSmallIcon(R.drawable.ic_stat_book_plus)
			setGroup(GROUP_NEW_CHAPTERS)
			val style = NotificationCompat.InboxStyle(this)
			for (chapter in newChapters) {
				style.addLine(chapter.getLocalizedTitle(applicationContext.resources))
			}
			style.setSummaryText(representativeManga.title)
			style.setBigContentTitle(summary)
			setStyle(style)
			val intent = resolveDetailsIntent(representativeManga)
			setContentIntent(
				PendingIntentCompat.getActivity(
					applicationContext,
					id,
					intent,
					PendingIntent.FLAG_UPDATE_CURRENT,
					false,
				),
			)
			setVisibility(if (representativeManga.isNsfw()) VISIBILITY_SECRET else VISIBILITY_PRIVATE)
			setShortcutId(representativeManga.id.toString())
			applyCommonSettings(this)
		}
		return NotificationInfo(id, TAG, builder.build(), representativeManga, newChapters.size)
	}

	fun createGroupNotification(
		notifications: List<NotificationInfo>
	): Notification? {
		if (notifications.size <= 1) {
			return null
		}
		val newChaptersCount = notifications.sumOf { it.newChapters }
		val builder = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
		with(builder) {
			val title = applicationContext.resources.getQuantityStringSafe(
				R.plurals.new_chapters,
				newChaptersCount,
				newChaptersCount,
			)
			setContentTitle(title)
			setContentText(notifications.joinToString { it.manga.title })
			setSmallIcon(R.drawable.ic_stat_book_plus)
			val style = NotificationCompat.InboxStyle(this)
			for (item in notifications) {
				style.addLine(
					applicationContext.getString(R.string.new_chapters_pattern, item.manga.title, item.newChapters),
				)
			}
			style.setBigContentTitle(title)
			setStyle(style)
			setNumber(newChaptersCount)
			setGroup(GROUP_NEW_CHAPTERS)
			setGroupSummary(true)
			setVisibility(
				if (notifications.any { it.manga.isNsfw() }) {
					VISIBILITY_SECRET
				} else {
					VISIBILITY_PRIVATE
				},
			)
			val intent = AppRouter.homeIntent(applicationContext)
			setContentIntent(
				PendingIntentCompat.getActivity(
					applicationContext,
					GROUP_NOTIFICATION_ID,
					intent,
					PendingIntent.FLAG_UPDATE_CURRENT,
					false,
				),
			)
			applyCommonSettings(this)
		}
		return builder.build()
	}

	private suspend fun resolveDetailsIntent(content: Content) = AppRouter.detailsIntent(
		applicationContext,
		workResolver.resolveByMangaId(content.id).entityId?.let { entityId ->
			DetailsOrigin.EntityGraph(
				entityId = entityId,
				initialProjectionLocalMangaId = content.id,
			)
		} ?: DetailsOrigin.LocalMangaContent(ParcelableContent(content)),
	)

	fun updateChannels() {
		val manager = NotificationManagerCompat.from(applicationContext)
		manager.deleteNotificationChannel(LEGACY_CHANNEL_ID)
		manager.deleteNotificationChannel(LEGACY_CHANNEL_ID_HISTORY)
		manager.deleteNotificationChannelGroup(LEGACY_CHANNELS_GROUP_ID)

		val channel = NotificationChannelCompat.Builder(CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_DEFAULT)
			.setName(applicationContext.getString(R.string.new_chapters))
			.setDescription(applicationContext.getString(R.string.show_notification_new_chapters_on))
			.setShowBadge(true)
			.setLightColor(ContextCompat.getColor(applicationContext, R.color.blue_primary))
			.setVibrationEnabled(settings.notificationVibrate)
			.build()
		manager.createNotificationChannel(channel)
	}

	private fun applyCommonSettings(builder: NotificationCompat.Builder) {
		builder.setAutoCancel(true)
		builder.setCategory(NotificationCompat.CATEGORY_SOCIAL)
		builder.priority = NotificationCompat.PRIORITY_DEFAULT
		if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
			builder.setSound(settings.notificationSound)
			var defaults = if (settings.notificationLight) {
				builder.setLights(ContextCompat.getColor(applicationContext, R.color.blue_primary), 1000, 5000)
				NotificationCompat.DEFAULT_LIGHTS
			} else 0
			if (settings.notificationVibrate) {
				builder.setVibrate(longArrayOf(500, 500, 500, 500))
				defaults = defaults or NotificationCompat.DEFAULT_VIBRATE
			}
			builder.setDefaults(defaults)
		}
	}

	class NotificationInfo(
		val id: Int,
		val tag: String,
		val notification: Notification,
		val manga: Content,
		val newChapters: Int,
	)

	companion object {

		const val CHANNEL_ID = "tracker_chapters"
		const val GROUP_NOTIFICATION_ID = 36
		const val GROUP_NEW_CHAPTERS = "org.skepsun.kototoro.NEW_CHAPTERS"
		const val TAG = "tracker"

		private const val LEGACY_CHANNELS_GROUP_ID = "trackers"
		private const val LEGACY_CHANNEL_ID_HISTORY = "track_history"
		private const val LEGACY_CHANNEL_ID = "tracking"
	}
}
