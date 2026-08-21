package org.skepsun.kototoro.core.github

import android.os.Parcelable
import kotlinx.parcelize.IgnoredOnParcel
import kotlinx.parcelize.Parcelize

enum class AppUpdateSource(val value: String) {
    GITHUB("github"),
    GITCODE("gitcode");

    companion object {
        fun fromValue(value: String?): AppUpdateSource? = entries.find { it.value == value }
    }
}

@Parcelize
data class AppVersion(
    val id: Long,
    val name: String,
    val url: String,
    val apkSize: Long,
    val apkUrl: String,
    val patchSize: Long? = null,
    val patchUrl: String? = null,
    val description: String,
    val source: AppUpdateSource = AppUpdateSource.GITHUB,
) : Parcelable {

    @IgnoredOnParcel
    val versionId = VersionId(name)
}
