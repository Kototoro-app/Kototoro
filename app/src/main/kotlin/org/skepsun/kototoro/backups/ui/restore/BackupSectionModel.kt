package org.skepsun.kototoro.backups.ui.restore

import androidx.annotation.StringRes
import org.skepsun.kototoro.R
import org.skepsun.kototoro.backups.domain.BackupSection
import org.skepsun.kototoro.list.ui.ListModelDiffCallback
import org.skepsun.kototoro.list.ui.model.ListModel

data class BackupSectionModel(
	val section: BackupSection,
	val isChecked: Boolean,
	val isEnabled: Boolean,
) : ListModel {

	@get:StringRes
	val titleResId: Int
			get() = when (section) {
				BackupSection.INDEX -> 0 // should not appear here
				BackupSection.HISTORY -> R.string.history
				BackupSection.CATEGORIES -> R.string.favourites_categories
				BackupSection.FAVOURITES -> R.string.favourites
				BackupSection.SETTINGS -> R.string.settings
				BackupSection.SETTINGS_READER_GRID -> R.string.reader_actions
				BackupSection.BOOKMARKS -> R.string.bookmarks
				BackupSection.SOURCES -> R.string.remote_sources
				BackupSection.EXTENSION_REPOS -> R.string.manage_extension_repositories
				BackupSection.SCROBBLING -> R.string.tracking
				BackupSection.TRACKS -> R.string.feed
				BackupSection.TRACK_LOGS -> R.string.updates
				BackupSection.PROJECTIONS -> 0
				BackupSection.STATS -> R.string.statistics
				BackupSection.SAVED_FILTERS -> R.string.saved_filters
				BackupSection.AUTH -> R.string.auth_title
				BackupSection.ENTITY_GRAPH_ENTITIES -> 0
				BackupSection.ENTITY_GRAPH_BINDINGS -> 0
				BackupSection.ENTITY_GRAPH_RELATIONS -> 0
				BackupSection.ENTITY_GRAPH_PREFS -> 0
				BackupSection.WORK_HISTORY -> 0
				BackupSection.WORK_FAVOURITES -> 0
				BackupSection.WORK_STATS -> 0
			}

	override fun areItemsTheSame(other: ListModel): Boolean {
		return other is BackupSectionModel && other.section == section
	}

	override fun getChangePayload(previousState: ListModel): Any? {
		if (previousState !is BackupSectionModel) {
			return null
		}
		return if (previousState.isEnabled != isEnabled) {
			ListModelDiffCallback.PAYLOAD_ANYTHING_CHANGED
		} else if (previousState.isChecked != isChecked) {
			ListModelDiffCallback.PAYLOAD_CHECKED_CHANGED
		} else {
			super.getChangePayload(previousState)
		}
	}
}
