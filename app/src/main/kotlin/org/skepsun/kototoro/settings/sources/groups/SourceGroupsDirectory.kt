package org.skepsun.kototoro.settings.sources.groups

import java.io.File

/** Location of the stored source groups, shared by the repository and the backup restore that swaps it. */
object SourceGroupsDirectory {

	private const val NAME = "source_groups"
	private const val PREVIOUS_NAME = "source_groups.previous"

	/** Held by the restore while it swaps the directory, so a reader never sees (or repairs) a half-done swap. */
	val lock = Any()

	fun previous(filesDir: File): File = File(filesDir, PREVIOUS_NAME)

	/** The groups directory; an interrupted swap that left the only copy aside is put back first. */
	fun resolve(filesDir: File): File = synchronized(lock) {
		val dir = File(filesDir, NAME)
		if (!dir.exists()) {
			val previous = previous(filesDir)
			if (previous.exists()) previous.renameTo(dir)
		}
		dir
	}
}
