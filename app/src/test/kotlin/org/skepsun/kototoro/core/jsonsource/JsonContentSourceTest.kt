package org.skepsun.kototoro.core.jsonsource

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.core.db.entity.JsonSourceEntity
import org.skepsun.kototoro.core.db.entity.JsonSourceType

class JsonContentSourceTest {

	@Test
	fun `tvbox source appends repository title`() {
		val source = JsonContentSource(entity(sourceTitle = "嗷呜"))

		assertEquals("配置中心（嗷呜）", source.displayName)
	}

	@Test
	fun `source without repository title keeps original name`() {
		val source = JsonContentSource(entity(sourceTitle = null))

		assertEquals("配置中心", source.displayName)
	}

	@Test
	fun `existing provider suffix is not duplicated`() {
		val source = JsonContentSource(
			entity(
				name = "配置中心（嗷呜）",
				sourceTitle = "嗷呜",
			),
		)

		assertEquals("配置中心（嗷呜）", source.displayName)
	}

	@Test
	fun `summary source appends provider name`() {
		val source = JsonSourceListSource(
			org.skepsun.kototoro.core.db.entity.JsonSourceSummary(
				id = "tvbox_config",
				name = "配置中心",
				type = JsonSourceType.TVBOX,
				config = """{"site":{"type":3,"api":"csp_AAConfigAmns"},"meta":{"sourceTitle":"集多"}}""",
				enabled = true,
			),
		)

		assertEquals("配置中心（集多）", source.displayName)
	}

	private fun entity(
		name: String = "配置中心",
		sourceTitle: String?,
	): JsonSourceEntity = JsonSourceEntity(
		id = "tvbox_config",
		name = name,
		type = JsonSourceType.TVBOX,
		config = """
			{"site":{"type":3,"api":"csp_AAConfigAmns"},"meta":{"sourceTitle":${sourceTitle?.let { "\"$it\"" } ?: "null"}}}
		""".trimIndent(),
		createdAt = 0,
		updatedAt = 0,
	)
}
