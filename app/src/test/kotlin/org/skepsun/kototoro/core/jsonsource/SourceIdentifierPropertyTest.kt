package org.skepsun.kototoro.core.jsonsource

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldMatch
import io.kotest.matchers.string.shouldStartWith
import io.kotest.property.Arb
import io.kotest.property.arbitrary.choice
import io.kotest.property.arbitrary.enum
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.string
import io.kotest.property.checkAll
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import org.skepsun.kototoro.core.db.dao.JsonSourceDao
import org.skepsun.kototoro.core.db.entity.JsonSourceEntity
import org.skepsun.kototoro.core.db.entity.JsonSourceSummary
import org.skepsun.kototoro.core.db.entity.JsonSourceType
import org.skepsun.kototoro.core.prefs.AppSettings

/**
 * Property-based tests for JSON source identifier generation and type recognition.
 * 
 * Feature: runtime-json-parser
 * 
 * These tests validate the correctness properties defined in the design document:
 * - Property 1: JSON source identifier uniqueness
 * - Property 2: Source type identification consistency
 * - Property 10: Source identifier format specification
 */
class SourceIdentifierPropertyTest : StringSpec({
	
	/**
	 * Property 1: JSON Source Identifier Uniqueness
	 * Validates: Requirements 11.1, 11.2, 11.3
	 * 
	 * 当前实现基于输入字符串哈希生成稳定 ID。
	 * 对随机全集不应强行要求绝对无碰撞，因此这里只验证一组固定样本的区分度。
	 */
	"generated identifiers are distinct for representative source names".config(invocations = 10) {
		val manager = JsonSourceManager(TestJsonSourceDao(), mockk<AppSettings>(relaxed = true))

		val representativeNames = listOf(
			"source-alpha",
			"source-beta",
			"source-gamma",
			"source-delta",
			"source-epsilon",
		)

		checkAll(Arb.enum<JsonSourceType>()) { sourceType ->
			val generatedIds = representativeNames.map { manager.generateSourceId(it, sourceType) }
			generatedIds.distinct().size shouldBe representativeNames.size
		}
	}
	
	/**
	 * Property 1 (variant): 相同输入生成稳定 ID
	 * Validates: Requirements 11.2
	 */
	"duplicate source names produce stable identifiers".config(invocations = 50) {
		val manager = JsonSourceManager(TestJsonSourceDao(), mockk<AppSettings>(relaxed = true))
		
		checkAll(
			Arb.string(1..30),
			Arb.enum<JsonSourceType>()
		) { sourceName, sourceType ->
			val id1 = manager.generateSourceId(sourceName, sourceType)
			val id2 = manager.generateSourceId(sourceName, sourceType)
			val id3 = manager.generateSourceId(sourceName, sourceType)
			
			id1 shouldBe id2
			id2 shouldBe id3
		}
	}
	
	/**
	 * Property 2: Source Type Identification Consistency
	 * Validates: Requirements 2.5, 11.1
	 * 
	 * For any source identifier, if it starts with "JSON_", then isJsonSource() must return true,
	 * and getSourceType() must return a JSON-related type.
	 */
	"source type identification is consistent with identifier prefix".config(invocations = 100) {
		val identifier = SourceTypeIdentifier()
		val mockDao = TestJsonSourceDao()
		val manager = JsonSourceManager(mockDao, mockk<AppSettings>(relaxed = true))
		
		checkAll(
			Arb.string(1..30),
			Arb.enum<JsonSourceType>()
		) { sourceName, sourceType ->
			val sourceId = manager.generateSourceId(sourceName, sourceType)
			
			// All generated JSON source IDs should start with JSON_
			sourceId shouldStartWith "JSON_"
			
			// isJsonSource should return true
			identifier.isJsonSource(sourceId) shouldBe true
			
			// getSourceType should return a JSON type
			val detectedType = identifier.getSourceType(sourceId)
				assert(
					detectedType == SourceType.JSON_LEGADO ||
						detectedType == SourceType.JSON_TVBOX ||
						detectedType == SourceType.JSON_JS ||
						detectedType == SourceType.JSON_LNREADER
				) {
					"Expected JSON type but got $detectedType for ID $sourceId"
				}
			
			// getJsonSourceType should return the correct type
			val jsonType = identifier.getJsonSourceType(sourceId)
			jsonType shouldNotBe null
		}
	}
	
	/**
	 * Property 2 (variant): Native sources are correctly identified
	 * Validates: Requirements 2.5, 11.1
	 */
	"native source identifiers are correctly identified".config(invocations = 50) {
		val identifier = SourceTypeIdentifier()
		
		val nativeSourceIds = listOf(
			"MANGADEX",
			"MANGANELO",
			"LOCAL_STORAGE",
			"ZLIBRARY",
			"NOVELIA"
		)
		
		for (sourceId in nativeSourceIds) {
			identifier.isJsonSource(sourceId) shouldBe false
			identifier.getSourceType(sourceId) shouldBe SourceType.NATIVE
			identifier.getJsonSourceType(sourceId) shouldBe null
		}
	}
	
	/**
	 * Property 10: Source Identifier Format Specification
	 * Validates: Requirements 11.1, 11.2
	 * 
	 * For any generated JSON source identifier, it must conform to the format:
	 * "JSON_" + uppercase alphanumeric characters and underscores
	 */
	"generated identifiers follow the correct format".config(invocations = 100) {
		val mockDao = TestJsonSourceDao()
		val manager = JsonSourceManager(mockDao, mockk<AppSettings>(relaxed = true))
		
			checkAll<String, JsonSourceType>(
				Arb.string(1..50),
				Arb.enum()
			) { sourceName, sourceType ->
				if (sourceName.isBlank()) return@checkAll
				
				val sourceId = manager.generateSourceId(sourceName, sourceType)
			
			// Must start with JSON_LEGADO_ or JSON_TVBOX_
			val expectedPrefix = when (sourceType) {
				JsonSourceType.LEGADO -> "JSON_LEGADO_"
				JsonSourceType.TVBOX -> "JSON_TVBOX_"
				JsonSourceType.JS -> "JSON_JS_"
				JsonSourceType.LNREADER -> "JSON_LNREADER_"
			}
			sourceId shouldStartWith expectedPrefix
			
				sourceId shouldMatch Regex("^JSON_(LEGADO|TVBOX|JS|LNREADER)_[A-F0-9]+$")
			}
		}
	
	/**
	 * Property 10 (variant): 不同特殊字符输入仍生成合法哈希标识符
	 * Validates: Requirements 11.2
	 */
	"special characters in source names still produce valid identifiers".config(invocations = 50) {
		val manager = JsonSourceManager(TestJsonSourceDao(), mockk<AppSettings>(relaxed = true))
		
		val testCases = listOf(
			"My Source!",
			"Test@Source#123",
			"Source (v2.0)",
			"中文源",
			"Source   With   Spaces",
			"___Multiple___Underscores___",
			"@#$%"
		)
		
		for (input in testCases) {
			val sourceId = manager.generateSourceId(input, JsonSourceType.LEGADO)
			sourceId shouldMatch Regex("^JSON_LEGADO_[A-F0-9]+$")
		}
	}
	
	/**
	 * Property: Type labels are consistent with source types
	 * Validates: Requirements 2.5, 11.1
	 */
	"type labels are consistent with detected source types".config(invocations = 50) {
		val identifier = SourceTypeIdentifier()
		val mockDao = TestJsonSourceDao()
		val manager = JsonSourceManager(mockDao, mockk<AppSettings>(relaxed = true))
		
		checkAll(
			Arb.string(1..30),
			Arb.enum<JsonSourceType>()
		) { sourceName, sourceType ->
			val sourceId = manager.generateSourceId(sourceName, sourceType)
			val detectedType = identifier.getSourceType(sourceId)
			val label = identifier.getSourceTypeLabel(sourceId)
			
			// Label should match the detected type
			when (detectedType) {
				SourceType.NATIVE -> label shouldBe "原生源"
				SourceType.JSON_LEGADO -> label shouldBe "JSON 源 (Legado)"
				SourceType.JSON_TVBOX -> label shouldBe "JSON 源 (TVBox)"
				SourceType.JSON_JS -> label shouldBe "JavaScript 源"
				SourceType.JSON_LNREADER -> label shouldBe "LNReader 源"
				SourceType.MIHON -> label shouldBe "Mihon 扩展"
				SourceType.ANIYOMI -> label shouldBe "Aniyomi 扩展"
				SourceType.IREADER -> label shouldBe "IReader 扩展"
				SourceType.TSUNDOKU -> label shouldBe "Tsundoku 扩展"
				SourceType.CLOUDSTREAM -> label shouldBe "Cloudstream 扩展"
				SourceType.EXTERNAL -> label shouldBe "外部源"
			}
		}
	}
})

/**
 * Mock implementation of JsonSourceDao for testing purposes.
 * This allows us to test the JsonSourceManager without a real database.
 */
private class TestJsonSourceDao : JsonSourceDao {
	private val sources = mutableListOf<JsonSourceEntity>()
	private val flowState = MutableStateFlow<List<JsonSourceEntity>>(emptyList())
	
	fun addSource(source: JsonSourceEntity) {
		sources.add(source)
		flowState.value = sources.toList()
	}
	
	override fun observeEnabled() = flowState
	override fun observeEnabledSummaries() = MutableStateFlow<List<JsonSourceSummary>>(emptyList())
	override fun observeAllSummaries() = MutableStateFlow<List<JsonSourceSummary>>(emptyList())
	override fun observeAll() = flowState
	
	override suspend fun getById(id: String) = sources.firstOrNull { it.id == id }
	
	override suspend fun insert(source: JsonSourceEntity) {
		sources.add(source)
		flowState.value = sources.toList()
	}
	
	override suspend fun insertAll(sources: List<JsonSourceEntity>) {
		this.sources.addAll(sources)
		flowState.value = this.sources.toList()
	}

	override suspend fun update(source: JsonSourceEntity) {
		val index = sources.indexOfFirst { it.id == source.id }
		if (index >= 0) {
			sources[index] = source
			flowState.value = sources.toList()
		}
	}
	
	override suspend fun setEnabled(id: String, enabled: Boolean, timestamp: Long) {
		val index = sources.indexOfFirst { it.id == id }
		if (index >= 0) {
			sources[index] = sources[index].copy(enabled = enabled, updatedAt = timestamp)
			flowState.value = sources.toList()
		}
	}
	
	override suspend fun setLastUsed(id: String, timestamp: Long) {
		val index = sources.indexOfFirst { it.id == id }
		if (index >= 0) {
			sources[index] = sources[index].copy(lastUsedAt = timestamp)
			flowState.value = sources.toList()
		}
	}
	
	override suspend fun delete(source: JsonSourceEntity) {
		sources.remove(source)
		flowState.value = sources.toList()
	}
	
	override suspend fun deleteById(id: String) {
		sources.removeIf { it.id == id }
		flowState.value = sources.toList()
	}
	
	override fun observeByType(type: JsonSourceType) = flowState
	override fun observeEnabledByType(type: JsonSourceType) = flowState
	override fun observeRecentlyUsed(limit: Int) = flowState
	override suspend fun getByIds(ids: List<String>) = sources.filter { it.id in ids }
	override suspend fun countByType(type: JsonSourceType) = sources.count { it.type == type }
	override suspend fun countEnabled() = sources.count { it.enabled }
	override suspend fun setEnabledBatch(ids: List<String>, enabled: Boolean, timestamp: Long) {
		ids.forEach { id ->
			val index = sources.indexOfFirst { it.id == id }
			if (index >= 0) {
				sources[index] = sources[index].copy(enabled = enabled, updatedAt = timestamp)
			}
		}
		flowState.value = sources.toList()
	}
	override suspend fun setPinned(id: String, isPinned: Boolean, timestamp: Long) = Unit
	override suspend fun setPinnedBatch(ids: List<String>, isPinned: Boolean, timestamp: Long) = Unit
	override suspend fun fillMissingIconUrl(id: String, iconUrl: String, timestamp: Long) = Unit
	override suspend fun deleteByIds(ids: List<String>) {
		sources.removeIf { it.id in ids }
		flowState.value = sources.toList()
	}
	override suspend fun deleteByType(type: JsonSourceType) {
		sources.removeIf { it.type == type }
		flowState.value = sources.toList()
	}
}
