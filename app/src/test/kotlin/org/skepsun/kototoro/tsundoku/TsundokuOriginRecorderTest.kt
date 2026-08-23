package org.skepsun.kototoro.tsundoku

import eu.kanade.tachiyomi.source.Source
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.core.db.dao.SourceOriginsDao
import org.skepsun.kototoro.core.db.entity.SourceOriginEntity
import org.skepsun.kototoro.extensions.repo.InstalledExtensionSignatureValidator
import org.skepsun.kototoro.extensions.runtime.tachiyomi.TachiyomiSourceRejection

class TsundokuOriginRecorderTest {

    private val novelSource = object : Source {
        override val id: Long = 9001L
        override val name: String = "NovelFull"
    }

    private val secondSource = object : Source {
        override val id: Long = 9002L
        override val name: String = "AllNovel"
    }

    private fun success(
        pkgName: String = "eu.kanade.tachiyomi.novelextension.en.novelfull",
        versionCode: Long = 11L,
        versionName: String = "1.6.11",
        isNsfw: Boolean = false,
        sources: List<Source> = listOf(novelSource),
    ) = TsundokuLoadResult.Success(
        pkgName = pkgName,
        appName = "NovelFull",
        versionCode = versionCode,
        versionName = versionName,
        libVersion = 1.6,
        lang = "en",
        isNsfw = isNsfw,
        sources = sources,
    )

    private fun recorder(
        dao: SourceOriginsDao,
        digest: String? = "deadbeef",
    ): TsundokuOriginRecorder {
        val validator = mockk<InstalledExtensionSignatureValidator> {
            every { firstFingerprint(any()) } returns digest
        }
        return TsundokuOriginRecorder(dao, validator)
    }

    @Test
    fun `records strictly derived origin per successfully scanned source`() = runBlocking {
        val dao = mockk<SourceOriginsDao>(relaxed = true)
        val recorder = recorder(dao)
        val captured = slot<SourceOriginEntity>()

        recorder.recordLoadResults(listOf(success()))

        coVerify(exactly = 1) { dao.upsert(capture(captured)) }
        val origin = captured.captured
        assertEquals("TSUNDOKU_9001", origin.sourceKey)
        assertEquals("TSUNDOKU", origin.kind)
        assertEquals("NovelFull", origin.displayName)
        assertEquals("NOVEL", origin.contentType)
        assertEquals("eu.kanade.tachiyomi.novelextension.en.novelfull", origin.packageName)
        assertEquals("9001", origin.sourceId)
        assertEquals("1.6.11", origin.versionName)
        assertEquals(11L, origin.versionCode)
        assertEquals("deadbeef", origin.signingDigest)
        assertEquals(origin.lastSeenAt, origin.updatedAt)
    }

    @Test
    fun `nsfw package records hentai novel content type`() = runBlocking {
        val dao = mockk<SourceOriginsDao>(relaxed = true)
        val recorder = recorder(dao)
        val captured = slot<SourceOriginEntity>()

        recorder.recordLoadResults(listOf(success(isNsfw = true)))

        coVerify(exactly = 1) { dao.upsert(capture(captured)) }
        assertEquals("HENTAI_NOVEL", captured.captured.contentType)
    }

    @Test
    fun `every source in a multi-source package gets its own origin`() = runBlocking {
        val dao = mockk<SourceOriginsDao>(relaxed = true)
        val recorder = recorder(dao)

        recorder.recordLoadResults(listOf(success(sources = listOf(novelSource, secondSource))))

        val upserted = mutableListOf<SourceOriginEntity>()
        coVerify(exactly = 2) { dao.upsert(capture(upserted)) }
        assertEquals(setOf("TSUNDOKU_9001", "TSUNDOKU_9002"), upserted.map { it.sourceKey }.toSet())
    }

    @Test
    fun `failed packages do not upsert and never delete existing origins`() = runBlocking {
        val dao = mockk<SourceOriginsDao>(relaxed = true)
        val recorder = recorder(dao)

        recorder.recordLoadResults(
            listOf(
                TsundokuLoadResult.Error(
                    pkgName = "broken.pkg",
                    phase = "INSTANTIATION",
                    message = "factory boom",
                ),
                success(),
            ),
        )

        coVerify(exactly = 1) { dao.upsert(any()) }
        coVerify(exactly = 0) { dao.deleteByKey(any()) }
        coVerify(exactly = 0) { dao.deleteAll() }
    }

    @Test
    fun `unload with no packages never deletes origins`() = runBlocking {
        val dao = mockk<SourceOriginsDao>(relaxed = true)
        val recorder = recorder(dao)

        recorder.recordLoadResults(emptyList())

        coVerify(exactly = 0) { dao.upsert(any()) }
        coVerify(exactly = 0) { dao.deleteByKey(any()) }
        coVerify(exactly = 0) { dao.deleteAll() }
    }

    @Test
    fun `rejected sources are skipped without touching their origin`() = runBlocking {
        val dao = mockk<SourceOriginsDao>(relaxed = true)
        val recorder = recorder(dao)

        recorder.recordLoadResults(
            listOf(
                success().copy(
                    sources = emptyList(),
                    rejections = listOf(
                        TachiyomiSourceRejection(
                            className = "RejectedFactory",
                            reason = "factory threw",
                        ),
                    ),
                ),
            ),
        )

        coVerify(exactly = 0) { dao.upsert(any()) }
        coVerify(exactly = 0) { dao.deleteByKey(any()) }
    }

    @Test
    fun `missing signature records null digest without guessing`() = runBlocking {
        val dao = mockk<SourceOriginsDao>(relaxed = true)
        val recorder = recorder(dao, digest = null)
        val captured = slot<SourceOriginEntity>()

        recorder.recordLoadResults(listOf(success()))

        coVerify(exactly = 1) { dao.upsert(capture(captured)) }
        assertNull(captured.captured.signingDigest)
    }
}
