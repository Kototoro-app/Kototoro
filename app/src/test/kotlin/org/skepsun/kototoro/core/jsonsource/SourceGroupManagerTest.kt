package org.skepsun.kototoro.core.jsonsource

import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.TvType
import io.mockk.every
import io.mockk.mockk
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.cloudstream.model.CloudstreamSource
import org.skepsun.kototoro.core.db.entity.JsonSourceSummary
import org.skepsun.kototoro.core.db.entity.JsonSourceType
import org.skepsun.kototoro.core.model.ContentSource

class SourceGroupManagerTest {

	private val sourceGroupManager = SourceGroupManager(
		sourceTypeIdentifier = SourceTypeIdentifier(),
		jsonSourceManager = mockk(relaxed = true),
		json = Json,
	)

	@Test
	fun `cloudstream source is classified as video group`() {
		val api = mockk<MainAPI> {
			every { name } returns "Test Provider"
			every { lang } returns "en"
			every { supportedTypes } returns setOf(TvType.Movie)
		}
		val source = CloudstreamSource(
			api = api,
			pluginFileName = "test.cs3",
			pluginPackageName = "org.example.test",
		)

		val result = sourceGroupManager.getContentGroup(source)

		assertEquals(ContentGroup.VIDEO, result)
	}

	@Test
	fun `lnreader list source is classified as novel group`() {
		val source = JsonSourceListSource(
			JsonSourceSummary(
				id = "JSON_LNREADER_TEST",
				name = "Test LNReader source",
				type = JsonSourceType.LNREADER,
				enabled = true,
			),
		)

		val result = sourceGroupManager.getContentGroup(source)

		assertEquals(ContentGroup.NOVEL, result)
	}

	@Test
	fun `anonymous ireader source is classified as novel group`() {
		val source = ContentSource("IREADER_123")

		val result = sourceGroupManager.getContentGroup(source)

		assertEquals(ContentGroup.NOVEL, result)
	}

	@Test
	fun `anonymous cloudstream source is classified as video group`() {
		val source = ContentSource("CLOUDSTREAM_org_example_Test")

		val result = sourceGroupManager.getContentGroup(source)

		assertEquals(ContentGroup.VIDEO, result)
	}
}
