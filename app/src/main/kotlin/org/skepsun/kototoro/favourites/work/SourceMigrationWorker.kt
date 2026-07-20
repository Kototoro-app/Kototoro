package org.skepsun.kototoro.favourites.work

import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.skepsun.kototoro.core.db.entity.toContent
import org.skepsun.kototoro.core.db.entity.toContentTag
import org.skepsun.kototoro.core.parser.ContentDataRepository
import org.skepsun.kototoro.core.util.ext.checkNotificationPermission
import org.skepsun.kototoro.core.util.ext.trySetForeground
import org.skepsun.kototoro.favourites.data.FavouriteContent
import org.skepsun.kototoro.favourites.domain.AttachReadingSourceToEntityUseCase
import org.skepsun.kototoro.favourites.domain.EntityOrganizeRepository
import org.skepsun.kototoro.favourites.domain.MigrationItem
import org.skepsun.kototoro.favourites.domain.MigrationStatus
import org.skepsun.kototoro.favourites.domain.ReadingSourcePreviewAction
import org.skepsun.kototoro.parsers.util.runCatchingCancellable
import org.skepsun.kototoro.work.domain.WorkProjectionBindingResult
import org.skepsun.kototoro.R
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

private const val TAG = "SourceMigration"

@HiltWorker
class SourceMigrationWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val entityOrganizeRepository: EntityOrganizeRepository,
    private val attachReadingSourceToEntityUseCase: AttachReadingSourceToEntityUseCase,
    private val contentDataRepository: ContentDataRepository,
    private val notificationFactoryFactory: SourceMigrationNotificationFactory.Factory,
) : CoroutineWorker(appContext, params) {

    private val notificationFactory = notificationFactoryFactory.create(
        UUID.nameUUIDFromBytes(id.toString().toByteArray()),
    )

    override suspend fun getForegroundInfo(): ForegroundInfo {
        val notification = notificationFactory.createProgress(1, 0, 0, 0, null)
        return buildForegroundInfo(notification)
    }

    override suspend fun doWork(): Result {
        val targetSourceNames = inputData.getStringArray(KEY_TARGET_SOURCES)?.filter { it.isNotBlank() }.orEmpty()
        val selectedContentIds = inputData.getLongArray(KEY_SELECTED_CONTENT_IDS)?.toSet().orEmpty()
        val fromSourceName = inputData.getString(KEY_FROM_SOURCE)
        val concurrency = inputData.getInt(KEY_CONCURRENCY, 3)
        val previewMangaIds = inputData.getLongArray(KEY_PREVIEW_MANGA_IDS) ?: longArrayOf()
        val previewTargetIds = inputData.getLongArray(KEY_PREVIEW_TARGET_IDS) ?: longArrayOf()
        val previewActions = inputData.getIntArray(KEY_PREVIEW_ACTIONS) ?: intArrayOf()

        if (targetSourceNames.isEmpty()) {
            Log.e(TAG, "No target sources configured")
            return failureResult(applicationContext.getString(R.string.source_migration_error_no_target_sources))
        }
        if (
            previewMangaIds.isEmpty() ||
            previewMangaIds.size != previewTargetIds.size ||
            previewMangaIds.size != previewActions.size
        ) {
            Log.e(TAG, "No valid migration preview plan configured")
            return failureResult(applicationContext.getString(R.string.source_migration_error_no_valid_preview_plan))
        }

        Log.d(
            TAG,
            "Worker started: selectedIds=${selectedContentIds.size} from=$fromSourceName " +
                "targets=${targetSourceNames.joinToString()} concurrency=$concurrency",
        )

        val favouriteContents = loadFavouriteContents(selectedContentIds, fromSourceName)
        if (favouriteContents.isEmpty()) {
            Log.d(TAG, "No favorites to migrate")
            return failureResult(applicationContext.getString(R.string.source_migration_error_no_favourites))
        }
        val plan: Map<Long, PreviewPlanItem> = previewMangaIds.indices.associate { index: Int ->
            previewMangaIds[index] to PreviewPlanItem(
                targetContentId = previewTargetIds[index],
                action = ReadingSourcePreviewAction.entries[previewActions[index]],
            )
        }
        val plannedFavouriteIds = plan.keys
        val plannedFavourites = favouriteContents.filter { favourite: FavouriteContent ->
            favourite.manga.id in plannedFavouriteIds
        }
        if (plannedFavourites.isEmpty()) {
            Log.e(TAG, "No favorites matched accepted preview plan")
            return failureResult(applicationContext.getString(R.string.source_migration_error_preview_scope_mismatch))
        }

        val hasPermission = applicationContext.checkNotificationPermission(CHANNEL_ID_SOURCE_MIGRATION)
        val foregroundActive = if (hasPermission) {
            trySetForeground()
        } else {
            false
        }

        return withContext(Dispatchers.IO) {
            val items = plannedFavourites.map { fc ->
                MigrationItem(mangaId = fc.manga.id, title = fc.manga.title)
            }.toMutableList()

            val completedCount = AtomicInteger(0)
            val failedCount = AtomicInteger(0)
            val notFoundCount = AtomicInteger(0)
            val reusedCount = AtomicInteger(0)
            val attachedCount = AtomicInteger(0)
            val semaphore = Semaphore(concurrency)
            publishProgress(items.size, 0, 0, 0, 0, 0, foregroundActive, null)

            coroutineScope {
                plannedFavourites.mapIndexed { index, favourite ->
                    async {
                        semaphore.withPermit {
                            if (isStopped) return@async

                            val currentItem = items[index]
                            items[index] = currentItem.copy(status = MigrationStatus.SEARCHING)
                            publishProgress(
                                items.size,
                                completedCount.get(),
                                failedCount.get(),
                                notFoundCount.get(),
                                reusedCount.get(),
                                attachedCount.get(),
                                foregroundActive,
                                currentItem.title,
                            )

                            val planItem = plan[favourite.manga.id]
                            val match = planItem?.let {
                                contentDataRepository.findContentById(it.targetContentId, withChapters = false)
                            }

                            if (match == null) {
                                items[index] = currentItem.copy(
                                    status = MigrationStatus.NOT_FOUND,
                                    errorMessage = "No accepted preview target found",
                                )
                                notFoundCount.incrementAndGet()
                                publishProgress(
                                    items.size,
                                    completedCount.get(),
                                    failedCount.get(),
                                    notFoundCount.get(),
                                    reusedCount.get(),
                                    attachedCount.get(),
                                    foregroundActive,
                                    currentItem.title,
                                )
                                return@async
                            }

                            items[index] = currentItem.copy(status = MigrationStatus.MIGRATING)
                            val oldContent = favourite.manga.toContent(
                                tags = favourite.tags.mapTo(mutableSetOf()) { it.toContentTag() },
                                chapters = null,
                            )
                            val result = runCatchingCancellable {
                                attachReadingSourceToEntityUseCase(oldContent, match)
                            }
                            val bindingResult = result.getOrNull()
                            if (bindingResult is WorkProjectionBindingResult.Success) {
                                items[index] = currentItem.copy(status = MigrationStatus.SUCCESS)
                                completedCount.incrementAndGet()
                                when (planItem?.action) {
                                    ReadingSourcePreviewAction.ACTIVATE_EXISTING -> reusedCount.incrementAndGet()
                                    ReadingSourcePreviewAction.ATTACH_NEW -> attachedCount.incrementAndGet()
                                    null -> Unit
                                }
                            } else {
                                val error = when (bindingResult) {
                                    is WorkProjectionBindingResult.Conflict -> bindingResult.reason.name
                                    else -> result.exceptionOrNull()?.message ?: "unknown"
                                }
                                items[index] = currentItem.copy(status = MigrationStatus.ERROR, errorMessage = error)
                                failedCount.incrementAndGet()
                            }
                            publishProgress(
                                items.size,
                                completedCount.get(),
                                failedCount.get(),
                                notFoundCount.get(),
                                reusedCount.get(),
                                attachedCount.get(),
                                foregroundActive,
                                currentItem.title,
                            )
                        }
                    }
                }.awaitAll()
            }

            val completed = completedCount.get()
            val failed = failedCount.get()
            val notFound = notFoundCount.get()
            val reused = reusedCount.get()
            val attached = attachedCount.get()
            if (foregroundActive) {
                setForeground(
                    buildForegroundInfo(
                        notificationFactory.createFinished(items.size, completed, failed, notFound, reused, attached),
                    ),
                )
            }

            val progressData = workDataOf(
                KEY_FINISHED to true,
                KEY_TOTAL to items.size,
                KEY_COMPLETED to completed,
                KEY_FAILED to failed,
                KEY_NOT_FOUND to notFound,
                KEY_REUSED to reused,
                KEY_ATTACHED to attached,
                KEY_MESSAGE to applicationContext.getString(
                    R.string.entity_organize_reading_execute_feedback,
                    completed,
                    reused,
                    attached,
                    failed,
                    notFound,
                ),
            )
            setProgress(progressData)
            Result.success(progressData)
        }
    }

    private suspend fun loadFavouriteContents(
        selectedContentIds: Set<Long>,
        fromSourceName: String?,
    ): List<FavouriteContent> {
        return when {
            selectedContentIds.isNotEmpty() -> entityOrganizeRepository.listFavouriteContentsByMangaIds(selectedContentIds)
            !fromSourceName.isNullOrBlank() -> entityOrganizeRepository.listFavouriteContents(fromSourceName)
            else -> emptyList()
        }
    }

    private fun buildForegroundInfo(notification: android.app.Notification): ForegroundInfo {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(id.hashCode(), notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(id.hashCode(), notification)
        }
    }

    private suspend fun publishProgress(
        total: Int,
        completed: Int,
        failed: Int,
        notFound: Int,
        reused: Int,
        attached: Int,
        updateNotification: Boolean,
        currentTitle: String?,
    ) {
        setProgress(
            workDataOf(
                KEY_TOTAL to total,
                KEY_COMPLETED to completed,
                KEY_FAILED to failed,
                KEY_NOT_FOUND to notFound,
                KEY_REUSED to reused,
                KEY_ATTACHED to attached,
                KEY_CURRENT_TITLE to currentTitle,
            ),
        )
        if (updateNotification && !isStopped) {
            setForeground(
                buildForegroundInfo(
                    notificationFactory.createProgress(total, completed, failed, notFound, null),
                ),
            )
        }
    }

    private fun failureResult(message: String): Result {
        return Result.failure(
            workDataOf(
                KEY_FINISHED to true,
                KEY_TOTAL to 0,
                KEY_COMPLETED to 0,
                KEY_FAILED to 0,
                KEY_NOT_FOUND to 0,
                KEY_REUSED to 0,
                KEY_ATTACHED to 0,
                KEY_MESSAGE to message,
            ),
        )
    }

    private data class PreviewPlanItem(
        val targetContentId: Long,
        val action: ReadingSourcePreviewAction,
    )

    companion object {
        const val KEY_FROM_SOURCE = "from_source"
        const val KEY_TARGET_SOURCES = "target_sources"
        const val KEY_SELECTED_CONTENT_IDS = "selected_content_ids"
        const val KEY_PREVIEW_MANGA_IDS = "preview_manga_ids"
        const val KEY_PREVIEW_TARGET_IDS = "preview_target_ids"
        const val KEY_PREVIEW_ACTIONS = "preview_actions"
        const val KEY_CONCURRENCY = "concurrency"
        const val KEY_FINISHED = "finished"
        const val KEY_TOTAL = "total"
        const val KEY_COMPLETED = "completed"
        const val KEY_FAILED = "failed"
        const val KEY_NOT_FOUND = "not_found"
        const val KEY_REUSED = "reused"
        const val KEY_ATTACHED = "attached"
        const val KEY_CURRENT_TITLE = "current_title"
        const val KEY_MESSAGE = "message"
        const val WORK_TAG = "source_migration"
    }
}
