package org.skepsun.kototoro.video.player

import android.content.Context
import java.io.File

object MpvConfigManager {

	private const val FILE_NAME = "mpv.conf"

	fun configFile(context: Context): File = File(context.filesDir, FILE_NAME)

	fun read(context: Context): String {
		val file = configFile(context)
		return if (file.isFile) file.readText() else ""
	}

	fun write(context: Context, content: String) {
		val normalized = content.replace("\r\n", "\n").trimEnd()
		if (normalized.isBlank()) {
			reset(context)
			return
		}
		configFile(context).writeText("$normalized\n")
	}

	fun reset(context: Context): Boolean {
		val file = configFile(context)
		return !file.exists() || file.delete()
	}

	fun hasCustomConfig(context: Context): Boolean = configFile(context).isFile
}
