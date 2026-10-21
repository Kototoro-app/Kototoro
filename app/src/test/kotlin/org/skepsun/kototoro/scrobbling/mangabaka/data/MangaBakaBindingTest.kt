package org.skepsun.kototoro.scrobbling.mangabaka.data

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.core.db.MangaDatabase
import org.skepsun.kototoro.scrobbling.common.data.ScrobblingDao
import org.skepsun.kototoro.scrobbling.common.data.ScrobblingEntity
import org.skepsun.kototoro.scrobbling.common.data.rebindScrobblingToManga

class MangaBakaBindingTest {
    @Test
    fun `unbound entries sharing local zero ID bind only the selected remote work and media type`() = runBlocking {
        val dao = mockk<ScrobblingDao>(relaxed = true)
        val db = mockk<MangaDatabase> { every { getScrobblingDao() } returns dao }
        val selected = ScrobblingEntity(8, 92, 0, 377, "reading", 12, "Keep", 0.75f, "manga")
        val other = selected.copy(id = 93, targetId = 378)
        val otherType = selected.copy(mediaType = "novel")
        coEvery { dao.findByLocalManga(8, 0) } returns other
        coEvery { dao.findAllByTargetId(8, 377) } returns listOf(otherType, selected)
        val result = db.rebindScrobblingToManga(8, 0, 55, 377, "manga") { error("Must reuse remote entry") }
        assertEquals(selected.copy(mangaId = 55), result)
        coVerify { dao.delete(selected) }
        coVerify { dao.upsert(selected.copy(mangaId = 55)) }
        coVerify(exactly = 0) { dao.delete(other) }
        coVerify(exactly = 0) { dao.delete(otherType) }
    }
}
