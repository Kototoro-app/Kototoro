package org.skepsun.kototoro.local.data

enum class CacheDir(val dir: String) {

	THUMBS("image_cache"),
	FAVICONS("favicons"),
	PAGES("pages"),
	NOVELS("novels"),
	VIDEO("video"),
	    VIDEO_PROXY("video_proxy_cache"),
	    TORRENT("torrent_tmp"),
	    DANMAKU("danmaku_cache"),
    HTTP("http"),
    SUPER_RESOLUTION("sr_cache"),
    TtsAudio("tts_audio");
}
