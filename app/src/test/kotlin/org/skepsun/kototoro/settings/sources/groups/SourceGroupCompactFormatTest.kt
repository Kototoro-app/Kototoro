package org.skepsun.kototoro.settings.sources.groups

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SourceGroupCompactFormatTest {

	private fun group(states: List<SourceGroupSourceState>, schema: Int = SOURCE_GROUP_SCHEMA_VERSION) = SourceGroup(
		schema = schema,
		id = "g1",
		name = "Mixed",
		createdAt = 1L,
		sourceStates = states,
	)

	private fun plain(name: String, key: Int) = SourceGroupSourceState(name, enabled = true, pinned = false, sortKey = key)

	private fun sorted(states: List<SourceGroupSourceState>) = states.sortedWith(compareBy({ it.source }, { it.sortKey }))

	private val mixed = listOf(
		plain("A", 1),
		plain("B", 2),
		plain("C", 5),
		plain("D", 6),
		plain("E", 20),
		plain("F", 20),
		plain("JSON_1", 0),
		plain("JSON_2", 0),
		SourceGroupSourceState("OFF", enabled = false, pinned = false, sortKey = 3),
		SourceGroupSourceState("LOCK", enabled = true, pinned = false, sortKey = 4, locked = false),
		SourceGroupSourceState("PIN", enabled = true, pinned = true, sortKey = 7),
		SourceGroupSourceState("DOM", enabled = true, pinned = false, sortKey = 8, domain = "example.org"),
	)

	@Test
	fun `mixed group round trips to the same source states`() {
		val original = group(mixed)
		val decoded = SourceGroupJson.decode(SourceGroupJson.encode(original))
		assertEquals(sorted(mixed), sorted(decoded.sourceStates))
		assertTrue(decoded.isValid())
	}

	@Test
	fun `plain sources are written by name only`() {
		val text = SourceGroupJson.encode(group(mixed))
		assertTrue(text.contains("enabledSources"))
		assertTrue(!text.contains("\"source\": \"A\""))
		assertTrue(text.contains("\"source\": \"PIN\""))
	}

	@Test
	fun `legacy full format still decodes`() {
		val text = """
			{"schema":1,"id":"g1","name":"Old","createdAt":1,"sourceStates":[
			{"source":"A","enabled":true,"pinned":false,"sortKey":3},
			{"source":"B","enabled":false,"pinned":true,"sortKey":9,"locked":true,"domain":"x.org"}]}
		""".trimIndent()
		val decoded = SourceGroupJson.decode(text)
		assertEquals(1, decoded.schema)
		assertEquals(
			listOf(
				plain("A", 3),
				SourceGroupSourceState("B", enabled = false, pinned = true, sortKey = 9, locked = true, domain = "x.org"),
			),
			decoded.sourceStates,
		)
		assertTrue(decoded.isValid())
	}

	@Test
	fun `700 plain sources are smaller than the legacy shape`() {
		val states = (1..700).map { plain("SOURCE_$it", it) }
		val compact = SourceGroupJson.encode(group(states))
		val legacy = SourceGroupJson.json.encodeToString(
			kotlinx.serialization.builtins.ListSerializer(SourceGroupSourceState.serializer()),
			states,
		)
		assertTrue(compact.length < legacy.length / 2)
		assertEquals(states, SourceGroupJson.decode(compact).sourceStates)
	}
}
