package org.skepsun.kototoro.video.player

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class Anime4KHookShaderTest {

	@Test
	fun `parser separates hook passes and preserves shader source`() {
		val source = """
			// license
			//!DESC First
			//!HOOK MAIN
			//!BIND MAIN
			//!SAVE temp
			//!COMPONENTS 1
			vec4 hook() { return MAIN_tex(MAIN_pos); }
			//!DESC Second
			//!HOOK MAIN
			//!BIND temp
			//!WIDTH temp.w 2 *
			vec4 hook() { return temp_tex(temp_pos); }
		""".trimIndent()

		val passes = Anime4KHookShaderParser.parse(source)

		assertEquals(2, passes.size)
		assertEquals("temp", passes.first().save)
		assertEquals(1, passes.first().components)
		assertEquals(4, passes.last().components)
		assertEquals("temp.w 2 *", passes.last().widthExpression)
		assertTrue(passes.last().source.contains("temp_tex"))
	}

	@Test
	fun `postfix expressions resolve texture and output dimensions`() {
		val textures = mapOf("MAIN" to ShaderTextureSize(720, 400))
		val output = ShaderTextureSize(2304, 1280)

		assertEquals(1440.0, Anime4KHookExpression.evaluate("MAIN.w 2 *", textures, output))
		assertTrue(Anime4KHookExpression.evaluate("OUTPUT.w MAIN.w 1.2 * >", textures, output) == 1.0)
		assertFalse(Anime4KHookExpression.evaluate("OUTPUT.h MAIN.h 4 * >", textures, output) == 1.0)
	}
}
