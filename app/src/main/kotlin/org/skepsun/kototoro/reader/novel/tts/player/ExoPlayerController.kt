package org.skepsun.kototoro.reader.novel.tts.player

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.skepsun.kototoro.reader.novel.tts.model.AudioData

class ExoPlayerController(context: Context) {

    private val _currentItemIndex = MutableStateFlow<Int?>(null)
    val currentItemIndex = _currentItemIndex.asStateFlow()

    private val _playbackCompleted = MutableStateFlow(false)
    val playbackCompleted = _playbackCompleted.asStateFlow()

    private val player = ExoPlayer.Builder(context).build().apply {
        // 允许在锁屏和后台持续播放，防止休眠
        setWakeMode(androidx.media3.common.C.WAKE_MODE_NETWORK)

        // 设置音频属性，以便系统识别为媒体播放并自动处理音频焦点
        val audioAttributes = androidx.media3.common.AudioAttributes.Builder()
            .setUsage(androidx.media3.common.C.USAGE_MEDIA)
            .setContentType(androidx.media3.common.C.AUDIO_CONTENT_TYPE_SPEECH)
            .build()
        setAudioAttributes(audioAttributes, true)

        // 当耳机拔出时自动暂停播放
        setHandleAudioBecomingNoisy(true)

        // 禁止 Item 之间自动停顿，确保无缝衔接
        pauseAtEndOfMediaItems = false
        addListener(object : androidx.media3.common.Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                mediaItem?.mediaId?.toIntOrNull()?.let {
                    _currentItemIndex.value = it
                }
                if (mediaItem == null) {
                    _currentItemIndex.value = null
                }
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == androidx.media3.common.Player.STATE_ENDED) {
                    _playbackCompleted.value = true
                }
            }
        })
    }

    fun play(audioFlow: Flow<Pair<Int, AudioData>>) {
        _playbackCompleted.value = false
        CoroutineScope(Dispatchers.Main).launch {
            audioFlow.collect { (index, audio) ->
                val item = MediaItem.Builder()
                    .setUri(audio.uri)
                    .setMediaId(index.toString())
                    .build()

                player.addMediaItem(item)

                if (player.mediaItemCount == 1) {
                    // 第一次添加，直接准备并要求播放
                    player.prepare()
                    player.play()
                } else if (player.playbackState == androidx.media3.common.Player.STATE_ENDED) {
                    // 如果因为网络慢导致已经播放完之前所有的音频而处于 ENDED 状态，此时来了新音频，如果并未被用户暂停则自动继续播放
                    if (player.playWhenReady) {
                        player.seekToDefaultPosition(player.mediaItemCount - 1)
                        player.play()
                    }
                }

                trimQueueIfNeeded()
            }
        }
    }

    private fun trimQueueIfNeeded() {
        val max = 30
        if (player.mediaItemCount > max) {
            // Remove the earliest item that has already been played.
            // Exoplayer's media queue runs sequentially. Removing from 0 while playing further items
            // forces older items to be dropped to prevent OOM.
            if (player.currentMediaItemIndex > 0) {
                player.removeMediaItem(0)
            }
        }
    }

    fun stop() {
        player.stop()
        player.clearMediaItems()
        _playbackCompleted.value = false
    }

    fun resetCompletion() {
        _playbackCompleted.value = false
    }

    fun pause() {
        player.pause()
    }

    fun resume() {
        player.play()
    }

    fun seekNext() {
        if (player.hasNextMediaItem()) {
            player.seekToNextMediaItem()
        }
    }

    fun seekPrev() {
        if (player.hasPreviousMediaItem()) {
            player.seekToPreviousMediaItem()
        } else {
            player.seekTo(0)
        }
    }

    fun release() {
        player.release()
    }
}
