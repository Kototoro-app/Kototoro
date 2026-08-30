package org.skepsun.kototoro.tracker.work

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import org.skepsun.kototoro.R

class UpdateCheckRequestMessageTest : StringSpec({

    "started maps to the checking-for-updates prompt" {
        UpdateCheckRequest.Started.messageRes() shouldBe R.string.checking_for_updates
    }

    "in-flight check maps to the in-progress prompt" {
        UpdateCheckRequest.InFlight.messageRes() shouldBe R.string.updates_check_in_progress
    }

    "recently-checked maps to the cooldown prompt" {
        UpdateCheckRequest.TooSoon.messageRes() shouldBe R.string.updates_check_too_soon
    }

    "tracker disabled maps to the disabled prompt" {
        UpdateCheckRequest.TrackerDisabled.messageRes() shouldBe R.string.check_for_new_chapters_disabled
    }
})
