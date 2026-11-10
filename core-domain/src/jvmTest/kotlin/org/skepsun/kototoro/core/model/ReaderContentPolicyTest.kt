package org.skepsun.kototoro.core.model

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.parsers.model.ContentType

class ReaderContentPolicyTest {
    @Test
    fun `all persisted audiovisual variants are excluded`() {
        for (type in listOf(ContentType.VIDEO, ContentType.HENTAI_VIDEO, ContentType.OTHER)) {
            assertFalse(ReaderContentPolicy.acceptsSource("RESTORED", type))
        }
    }

    @Test
    fun `reading extension families and local books remain supported`() {
        for (name in listOf("MIHON_1", "IREADER_1", "TSUNDOKU_1", "JSON_LEGADO_1", "JSON_LNREADER_1", "LOCAL_NOVEL", "LOCAL")) {
            for (type in listOf(ContentType.MANGA, ContentType.MANHWA, ContentType.MANHUA, ContentType.NOVEL)) {
                assertTrue(ReaderContentPolicy.acceptsSource(name, type), "$name / $type")
            }
        }
    }

    @Test
    fun `retired providers cannot bypass the policy with misleading metadata`() {
        for (name in listOf("ANIYOMI_1", "CLOUDSTREAM_1", "JSON_TVBOX_1", "LOCAL_VIDEO")) {
            assertFalse(ReaderContentPolicy.acceptsSource(name, ContentType.MANGA))
        }
    }

    @Test
    fun `unavailable reading extensions keep their restored content type`() {
        assertTrue(ReaderContentPolicy.acceptsSnapshot("RESTORED", ContentType.NOVEL, ContentType.OTHER))
        assertFalse(ReaderContentPolicy.acceptsSnapshot("RESTORED", ContentType.VIDEO, ContentType.MANGA))
        assertFalse(ReaderContentPolicy.acceptsSnapshot("RESTORED", null, ContentType.OTHER))
    }
}
