package org.skepsun.kototoro.space.data

import android.util.Log
import javax.inject.Inject

enum class SpaceDiagnosticStage {
	INITIALIZED,
	ACTIVATED,
	REJECTED,
}

data class SpaceDiagnosticEvent(
	val stage: SpaceDiagnosticStage,
	val activeSpaceId: String,
	val targetSpaceId: String? = null,
	val reason: String? = null,
)

interface SpaceDiagnostics {
	fun record(event: SpaceDiagnosticEvent)
}

class LogcatSpaceDiagnostics @Inject constructor() : SpaceDiagnostics {
	override fun record(event: SpaceDiagnosticEvent) {
		Log.d(
			TAG,
			"stage=${event.stage} active=${event.activeSpaceId} " +
				"target=${event.targetSpaceId.orEmpty()} reason=${event.reason.orEmpty()}",
		)
	}

	private companion object {
		const val TAG = "EntitySpace"
	}
}
