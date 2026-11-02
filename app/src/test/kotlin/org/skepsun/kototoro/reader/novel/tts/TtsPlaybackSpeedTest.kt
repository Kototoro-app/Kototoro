package org.skepsun.kototoro.reader.novel.tts

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

class TtsPlaybackSpeedTest : StringSpec({

    "speed is clamped to the supported range" {
        TtsPlaybackSpeed.normalize(0.1f) shouldBe TtsPlaybackSpeed.MIN
        TtsPlaybackSpeed.normalize(10f) shouldBe TtsPlaybackSpeed.MAX
        TtsPlaybackSpeed.normalize(1.25f) shouldBe 1.25f
        TtsPlaybackSpeed.normalize(Float.NaN) shouldBe TtsPlaybackSpeed.DEFAULT
    }

    "every preset is inside the supported range" {
        TtsPlaybackSpeed.OPTIONS.forEach { TtsPlaybackSpeed.normalize(it) shouldBe it }
    }

    "labels are locale independent" {
        TtsPlaybackSpeed.label(1f) shouldBe "1.0×"
        TtsPlaybackSpeed.label(1.25f) shouldBe "1.25×"
        TtsPlaybackSpeed.label(0.5f) shouldBe "0.5×"
    }
})
