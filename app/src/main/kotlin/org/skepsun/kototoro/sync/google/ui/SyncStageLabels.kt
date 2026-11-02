package org.skepsun.kototoro.sync.google.ui

import android.content.Context
import org.skepsun.kototoro.R

private fun syncStageRes(stage: String): Int? = when (stage) {
	"list" -> R.string.sync_stage_list
	"download" -> R.string.sync_stage_download
	"merge" -> R.string.sync_stage_merge
	"apply" -> R.string.sync_stage_apply
	"upload" -> R.string.sync_stage_upload
	"repair" -> R.string.sync_stage_repair
	"build" -> R.string.sync_stage_build
	"normalize" -> R.string.sync_stage_normalize
	"prune" -> R.string.sync_stage_prune
	else -> null
}

fun Context.syncStageLabel(stage: String): String = syncStageRes(stage)?.let { getString(it) } ?: stage
