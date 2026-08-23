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

    test("anonymous TSUNDOKU_ wrapper shows a loading placeholder instead of the raw key") {
        // Reproduces a saved novel whose Tsundoku extension package has been uninstalled:
        // the row keeps the `TSUNDOKU_{id}` key until the live source can be resolved, and
        // the title must not render the raw key on screen.
        val anonymous = ContentSource("TSUNDOKU_1400001")

        anonymous.getTitle(context) shouldBe "Loading Tsundoku source..."
    }

    test("anonymous IREADER_ wrapper shows a loading placeholder instead of the raw key") {
        val anonymous = ContentSource("IREADER_10")

        anonymous.getTitle(context) shouldBe "Loading IReader source..."
    }
})
