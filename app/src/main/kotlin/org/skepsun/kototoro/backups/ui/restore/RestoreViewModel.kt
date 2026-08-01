package org.skepsun.kototoro.backups.ui.restore

import android.content.Context
import android.net.Uri
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runInterruptible
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream
import org.skepsun.kototoro.backups.data.model.BackupIndex
import org.skepsun.kototoro.backups.domain.BackupPayloadGuard
import org.skepsun.kototoro.backups.domain.BackupRestoreFormat
import org.skepsun.kototoro.backups.domain.BackupSection
import org.skepsun.kototoro.core.ui.BaseViewModel
import org.skepsun.kototoro.core.util.ext.printStackTraceDebug
import java.io.File
import java.io.FileNotFoundException
import java.io.InputStream
import java.util.Date
import java.util.EnumMap
import java.util.EnumSet
import java.util.zip.ZipInputStream
import javax.inject.Inject

@HiltViewModel
class RestoreViewModel @Inject constructor(
	@ApplicationContext context: Context,
) : BaseViewModel() {

	private val backupIndexJson = Json {
		coerceInputValues = true
		ignoreUnknownKeys = true
	}

	var uri: Uri? = null
		private set
	private val contentResolver = context.contentResolver
	private val cacheDir = context.cacheDir

	val availableEntries = MutableStateFlow<List<BackupSectionModel>>(emptyList())
	val backupDate = MutableStateFlow<Date?>(null)

	fun initialize(uri: Uri, restoreFormat: BackupRestoreFormat) {
		if (this.uri != null) return
		this.uri = uri
		launchLoadingJob(Dispatchers.Default) {
			loadBackupInfo(restoreFormat)
		}
	}

	private suspend fun loadBackupInfo(restoreFormat: BackupRestoreFormat) {
		val sourceUri = uri ?: throw FileNotFoundException()
		val sections = runInterruptible(Dispatchers.IO) {
			val tempFile = File.createTempFile("manual_backup_restore_inspect", ".bk.zip", cacheDir)
			try {
				contentResolver.openInputStream(sourceUri)?.use { input ->
					tempFile.outputStream().use { output -> input.copyTo(output) }
				} ?: throw FileNotFoundException()
				BackupPayloadGuard.requireRestorableWorkSnapshot(
					file = tempFile,
					operation = "manual backup restore inspection",
				)
				BackupPayloadGuard.requireRestoreFormat(tempFile, restoreFormat)
			} finally {
				if (tempFile.exists()) tempFile.delete()
			}
			ZipInputStream(contentResolver.openInputStream(sourceUri)).use { stream ->
				val result = EnumSet.noneOf(BackupSection::class.java)
				var entry = stream.nextEntry
				while (entry != null) {
					val s = BackupSection.of(entry)
					if (s != null) {
						result.add(s)
						if (s == BackupSection.INDEX) {
							backupDate.value = stream.readDate()
						}
					}
					stream.closeEntry()
					entry = stream.nextEntry
				}
				result
			}
		}
		availableEntries.value = BackupSection.entries.mapNotNull { entry ->
			if (entry == BackupSection.INDEX || !restoreFormat.supports(entry)) {
				return@mapNotNull null
			}
			val present = entry in sections
			val model = BackupSectionModel(
				section = entry,
				isChecked = present,
				isEnabled = present,
			)
			if (model.titleResId == 0) {
				return@mapNotNull null
			}
			model
		}
	}

	fun onItemClick(item: BackupSectionModel) {
		val map = availableEntries.value.associateByTo(EnumMap(BackupSection::class.java)) { it.section }
		map[item.section] = item.copy(isChecked = !item.isChecked)
		map.validate()
		availableEntries.value = map.values.sortedBy { it.section.ordinal }
	}

	fun getCheckedSections(): Set<BackupSection> = availableEntries.value
		.mapNotNullTo(EnumSet.noneOf(BackupSection::class.java)) {
			if (it.isChecked) it.section else null
		}
		.apply { add(BackupSection.INDEX) }

	/**
	 * Check for inconsistent user selection
	 * Favorites cannot be restored without categories
	 */
	private fun MutableMap<BackupSection, BackupSectionModel>.validate() {
		val favorites = this[BackupSection.FAVOURITES] ?: return
		val categories = this[BackupSection.CATEGORIES]
		if (categories?.isChecked == true) {
			if (!favorites.isEnabled) {
				this[BackupSection.FAVOURITES] = favorites.copy(isEnabled = true)
			}
		} else {
			if (favorites.isEnabled) {
				this[BackupSection.FAVOURITES] = favorites.copy(isEnabled = false, isChecked = false)
			}
		}
	}

	private fun InputStream.readDate(): Date? = runCatching {
		val index = backupIndexJson.decodeFromStream<List<BackupIndex>>(this)
		Date(index.single().createdAt)
	}.onFailure { e ->
		e.printStackTraceDebug()
	}.getOrNull()
}
