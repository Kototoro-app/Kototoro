package org.skepsun.kototoro.cloudstream.runtime

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.io.File

class CloudstreamProguardRulesTest : FunSpec({
    test("proguard rules preserve androidx.fragment.app and androidx.appcompat.app for plugins") {
        val candidatePaths = listOf(
            File("proguard-rules.pro"),
            File("app/proguard-rules.pro"),
            File("../app/proguard-rules.pro"),
        )
        val rulesFile = candidatePaths.firstOrNull { it.exists() }
        (rulesFile != null) shouldBe true
        val content = rulesFile!!.readText()
        content shouldContain "-keep class androidx.fragment.app.** { *; }"
        content shouldContain "-keep class androidx.appcompat.app.** { *; }"
    }
})
