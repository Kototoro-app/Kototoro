package org.skepsun.kototoro.entitygraph.work

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.hilt.work.WorkerAssistedFactory
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import org.skepsun.kototoro.core.prefs.AppSettings
import org.skepsun.kototoro.entitygraph.data.EntityGraphRepository

@HiltWorker
class EntityNameCollisionRepairWorker @AssistedInject constructor(
	@Assisted appContext: Context,
	@Assisted params: WorkerParameters,
	private val entityGraphRepository: EntityGraphRepository,
	private val settings: AppSettings,
) : CoroutineWorker(appContext, params) {

	override suspend fun doWork(): Result {
		return try {
			entityGraphRepository.repairLegacyEmptyNameHashCollisions()
			settings.isLegacyEntityNameCollisionRepairCompleted = true
			Result.success()
		} catch (error: Throwable) {
			error.printStackTrace()
			Result.retry()
		}
	}

	@AssistedFactory
	interface Factory : WorkerAssistedFactory<EntityNameCollisionRepairWorker>

	companion object {
		const val UNIQUE_WORK_NAME = "entity_name_collision_repair_v1"
	}
}
