package org.skepsun.kototoro.core.parser.tvbox

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class TVBoxActionMetadataTest {

	@Test
	fun `action metadata round trips opaque payload`() {
		val action = "quark-setting?cookie=a=b&token=opaque"

		assertEquals(action, TVBoxActionMetadata.decode(TVBoxActionMetadata.encode(action)))
	}

	@Test
	fun `unrelated source metadata is not treated as action`() {
		assertNull(TVBoxActionMetadata.decode("{\"type\":\"episode\",\"action\":\"login\"}"))
		assertNull(TVBoxActionMetadata.decode("not-json"))
	}

	@Test
	fun `action result reads standard message fields`() {
		assertEquals("登录成功", TVBoxActionResult.parse("{\"msg\":\"登录成功\"}")?.message)
		assertEquals("登录失败", TVBoxActionResult.parse("{\"error\":\"登录失败\"}")?.message)
		assertEquals("已清除", TVBoxActionResult.parse("已清除")?.message)
	}

	@Test
	fun `empty action response stays empty`() {
		assertNull(TVBoxActionResult.parse(""))
		assertNull(TVBoxActionResult.parse("{}")?.message)
	}
}
