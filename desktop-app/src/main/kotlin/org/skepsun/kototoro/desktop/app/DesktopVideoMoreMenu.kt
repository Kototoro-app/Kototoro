package org.skepsun.kototoro.desktop.app

import org.skepsun.kototoro.video.ui.compose.VideoPlayerMoreSection
import org.skepsun.kototoro.video.ui.compose.orderVideoPlayerMoreItems

/**
 * The player's low-frequency actions, in the order the 更多 menu lists them.
 *
 * Android's player menu keeps these out of the control row; the desktop row loses its trailing buttons as soon as the
 * window is narrow or the font is large, so they need a menu of their own instead of being pushed off to the side.
 */
internal enum class DesktopPlayerMoreItem { ENHANCEMENT, QUALITY, SPEED, AUDIO, SUBTITLES, PLAYBACK_INFO }

/** What the playing video can actually do; the menu shows an entry only for a capability that exists. */
internal data class DesktopPlayerMoreCapabilities(
    val streamCount: Int = 1,
    val audioTrackCount: Int = 1,
    val subtitleTrackCount: Int = 0,
) {
    val hasQualityChoice: Boolean get() = streamCount > 1
    val hasAudioChoice: Boolean get() = audioTrackCount > 1
    val hasSubtitleChoice: Boolean get() = subtitleTrackCount > 0
}

/**
 * The 更多 menu's entries.
 *
 * [DesktopPlayerMoreItem.ENHANCEMENT] is always offered: the desktop's upscaling is not something the video declares,
 * it is a switch the viewer owns. The rest follow the file — a single-stream video has nothing to pick between.
 */
internal fun desktopPlayerMoreItems(capabilities: DesktopPlayerMoreCapabilities): List<DesktopPlayerMoreItem> =
    // Ordered by the shared section rule: the codec dump is declared here with the everyday actions but always lands
    // after them, which is the whole point of reading diagnostics last.
    orderVideoPlayerMoreItems(
        buildList {
            add(DesktopPlayerMoreItem.PLAYBACK_INFO)
            add(DesktopPlayerMoreItem.ENHANCEMENT)
            if (capabilities.hasQualityChoice) add(DesktopPlayerMoreItem.QUALITY)
            add(DesktopPlayerMoreItem.SPEED)
            if (capabilities.hasAudioChoice) add(DesktopPlayerMoreItem.AUDIO)
            if (capabilities.hasSubtitleChoice) add(DesktopPlayerMoreItem.SUBTITLES)
        },
        ::desktopPlayerMoreSection,
    )

/** Where [item] sorts in the menu; the player draws a separator before the diagnostics section. */
internal fun desktopPlayerMoreSection(item: DesktopPlayerMoreItem): VideoPlayerMoreSection = when (item) {
    DesktopPlayerMoreItem.ENHANCEMENT, DesktopPlayerMoreItem.QUALITY -> VideoPlayerMoreSection.FREQUENT
    DesktopPlayerMoreItem.SPEED, DesktopPlayerMoreItem.AUDIO, DesktopPlayerMoreItem.SUBTITLES ->
        VideoPlayerMoreSection.TOOLS
    DesktopPlayerMoreItem.PLAYBACK_INFO -> VideoPlayerMoreSection.DIAGNOSTICS
}

/**
 * The rows of the playback-info dialog, as label to value.
 *
 * mpv answers nothing before a file is loaded and refuses properties it does not know, so a missing value is an
 * explicit dash rather than a blank row: a diagnostics screen that hides what it could not read is worse than none.
 */
internal fun desktopPlayerDiagnosticsRows(values: Map<String, String>): List<Pair<String, String>> {
    fun read(key: String): String = values[key]?.takeIf { it.isNotBlank() } ?: desktopText("videoInfo.unavailable")
    val width = values["width"]?.takeIf { it.isNotBlank() }
    val height = values["height"]?.takeIf { it.isNotBlank() }
    val resolution = if (width != null && height != null) "$width × $height" else desktopText("videoInfo.unavailable")
    return listOf(
        desktopText("videoInfo.decoder") to read("hwdec-current"),
        desktopText("videoInfo.renderer") to read("vo"),
        desktopText("videoInfo.videoCodec") to read("video-codec"),
        desktopText("videoInfo.audioCodec") to read("audio-codec-name"),
        desktopText("videoInfo.resolution") to resolution,
        desktopText("videoInfo.frameRate") to read("container-fps"),
        desktopText("videoInfo.version") to read("mpv-version"),
    )
}

/** Each entry's row tag, so the menu can be driven from tests without matching translated text. */
internal fun desktopPlayerMoreTag(item: DesktopPlayerMoreItem): String = "video-more:${item.name}"

/** Each entry's label, taken from the same strings the individual menus use. */
internal fun desktopPlayerMoreLabel(item: DesktopPlayerMoreItem): String = when (item) {
    DesktopPlayerMoreItem.ENHANCEMENT -> desktopText("video.enhancement")
    DesktopPlayerMoreItem.QUALITY -> desktopText("video.more.quality")
    DesktopPlayerMoreItem.SPEED -> desktopText("video.more.speed")
    DesktopPlayerMoreItem.AUDIO -> desktopText("video.more.audio")
    DesktopPlayerMoreItem.SUBTITLES -> desktopText("video.subtitles")
    DesktopPlayerMoreItem.PLAYBACK_INFO -> desktopText("video.playbackInfo")
}
