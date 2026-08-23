package org.skepsun.kototoro.core.model

import android.content.Context
import eu.kanade.tachiyomi.source.Source
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.mockk.mockk
import org.skepsun.kototoro.tsundoku.model.TsundokuNovelSource

class ContentSourceTitleTest : FunSpec({

    val context = mockk<Context>(relaxed = true)

    test("Tsundoku novel source title uses the human-readable display name, not the key") {
        val source = TsundokuNovelSource(
            upstreamSource = object : Source {
                override val id: Long = 3273374795580060030L
                override val name: String = "AllNovelFull"
                override val lang: String = "en"
            },
            pkgName = "eu.kanade.tachiyomi.novelextension.en.allnovelfull",
            isNsfw = false,
        )

        source.name shouldBe "TSUNDOKU_3273374795580060030"
        source.displayName shouldBe "AllNovelFull"

        val title = source.getTitle(context)
        title shouldBe "AllNovelFull"
        title shouldNotBe "TSUNDOKU_3273374795580060030"
    }

    test("Tsundoku novel source title includes the language suffix when present") {
        val source = TsundokuNovelSource(
            upstreamSource = object : Source {
                override val id: Long = 7L
                override val name: String = "My Novel"
                override val lang: String = "zh"
            },
            pkgName = "org.tsundoku.test",
            isNsfw = false,
            hasLanguageSuffix = true,
        )

        source.getTitle(context) shouldBe "My Novel (中文)"
    }
})
