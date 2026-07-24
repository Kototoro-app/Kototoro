package org.skepsun.kototoro.video.player

import android.content.Context
import android.util.AttributeSet
import `is`.xyz.mpv.BaseMPVView

class CustomMpvView @JvmOverloads constructor(
	context: Context,
	attrs: AttributeSet? = null,
) : BaseMPVView(context, attrs) {

	override fun initOptions() {
		setVo("gpu")
		mpv.setOptionString("hwdec", "auto")
		mpv.setOptionString("ao", "audiotrack,opensles")
		mpv.setOptionString("gpu-context", "android")
		mpv.setOptionString("keep-open", "yes")
		mpv.setOptionString("config", "yes")
		mpv.setOptionString("config-dir", context.filesDir.path)

		// 关键比例参数，避免旋转/PiP 后出现裁切或偏移
		mpv.setOptionString("keepaspect", "yes")
		mpv.setOptionString("panscan", "0.0")
		mpv.setOptionString("video-aspect-override", "-1")

		// Subtitle options: ensure embedded and external subtitles are detected and rendered
		mpv.setOptionString("sid", "auto")                       // Auto-select first subtitle track
		mpv.setOptionString("sub-visibility", "yes")             // Enable subtitle rendering
		mpv.setOptionString("sub-auto", "fuzzy")                 // Auto-load external subtitle files
		mpv.setOptionString("demuxer-mkv-subtitle-preroll", "yes") // Preload MKV embedded subtitles
		mpv.setOptionString("subs-with-matching-audio", "yes")   // Show subs even when audio language matches
		mpv.setOptionString("blend-subtitles", "yes")            // Render subs into video frame (fixes gpu-next OSD issue)
		mpv.setOptionString("sub-font-size", "55")               // Default subtitle font size
		mpv.setOptionString("sub-color", "#FFFFFFFF")            // White subtitle text
		mpv.setOptionString("sub-border-size", "3")              // Black border for readability
		mpv.setOptionString("sub-border-color", "#FF000000")     // Black border color
	}

	override fun postInitOptions() = Unit

	override fun observeProperties() = Unit
}
