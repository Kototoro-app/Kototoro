package org.skepsun.kototoro.main.ui.compose

import org.skepsun.kototoro.R
import org.skepsun.kototoro.parsers.model.ContentType

internal enum class MainFabMode {
    HIDDEN,
    CONTINUE_READING,
}

internal enum class MainResumeAction(
    val iconRes: Int,
    val contentDescriptionRes: Int,
) {
    READ(R.drawable.ic_read, R.string._continue),
    PLAY(R.drawable.ic_play, R.string._continue_play),
}

internal fun resolveMainFabMode(
    resumeEnabled: Boolean,
): MainFabMode = when {
    resumeEnabled -> MainFabMode.CONTINUE_READING
    else -> MainFabMode.HIDDEN
}

internal fun resolveMainResumeAction(
    contentType: ContentType?,
    looksLikeVideoContent: Boolean,
): MainResumeAction = when {
    contentType == ContentType.VIDEO || contentType == ContentType.HENTAI_VIDEO -> MainResumeAction.PLAY
    looksLikeVideoContent -> MainResumeAction.PLAY
    else -> MainResumeAction.READ
}
