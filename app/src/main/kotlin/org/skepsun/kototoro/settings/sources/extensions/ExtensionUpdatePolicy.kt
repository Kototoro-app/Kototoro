package org.skepsun.kototoro.settings.sources.extensions

import org.skepsun.kototoro.extensions.repo.RepoAvailableExtension

internal fun RepoAvailableExtension.isNewerThanInstalled(installedVersionCode: Long?): Boolean {
	return installedVersionCode != null && versionCode > installedVersionCode
}
