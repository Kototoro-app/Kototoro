package org.skepsun.kototoro.space.data

import org.skepsun.kototoro.core.prefs.AppSettings
import javax.inject.Inject

interface SpaceLocalDataSource {
	fun readActiveSpaceId(): String
	fun writeActiveSpaceId(value: String)
}

class AppSettingsSpaceLocalDataSource @Inject constructor(
	private val settings: AppSettings,
) : SpaceLocalDataSource {

	override fun readActiveSpaceId(): String = settings.activeSpaceId

	override fun writeActiveSpaceId(value: String) {
		settings.activeSpaceId = value
	}
}
