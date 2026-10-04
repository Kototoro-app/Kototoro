package org.skepsun.kototoro.backups.data.model

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.skepsun.kototoro.BuildConfig
import org.skepsun.kototoro.list.domain.ListSortOrder
import org.skepsun.kototoro.list.domain.ReadingProgress

/**
 * :core-backup cannot see the app's UI types, so it carries two persisted defaults as literals. These tests keep them
 * from drifting away from the app values they stand for, and cover the BuildConfig based BackupIndex factory that
 * stayed in the app.
 */
class BackupSharedConstantsTest : FunSpec({

    test("the shared default category order is the NEWEST sort order") {
        BACKUP_DEFAULT_CATEGORY_ORDER shouldBe ListSortOrder.NEWEST.name
    }

    test("the shared 'no progress' marker equals ReadingProgress.PROGRESS_NONE") {
        BACKUP_PROGRESS_NONE shouldBe ReadingProgress.PROGRESS_NONE
    }

    test("the BackupIndex factory stamps this build's application id and version") {
        val index = BackupIndex(deviceId = "device-1", dataVersion = 3, exportedAt = 99L)
        index.appId shouldBe BuildConfig.APPLICATION_ID
        index.appVersion shouldBe BuildConfig.VERSION_CODE
        index.transportGeneration shouldBe BackupIndex.WRITER_GENERATION_V3
        index.semanticSchemaVersion shouldBe BackupIndex.CURRENT_SYNC_SCHEMA_VERSION
        index.deviceId shouldBe "device-1"
        index.dataVersion shouldBe 3
        index.createdAt shouldBe 99L
        index.exportedAt shouldBe 99L
    }

    test("the Kotatsu compatibility index uses writer generation 1 and schema 1") {
        val index = BackupIndex.forKotatsuCompatibility(exportedAt = 5L)
        index.appId shouldBe BuildConfig.APPLICATION_ID
        index.transportGeneration shouldBe BackupIndex.WRITER_GENERATION_V1
        index.semanticSchemaVersion shouldBe 1
    }
})
