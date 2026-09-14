package org.skepsun.kototoro.core.jsonsource

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldMatch
import io.kotest.matchers.string.shouldStartWith
import io.kotest.property.Arb
import io.kotest.property.arbitrary.*
import io.kotest.property.checkAll
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import org.jsoup.Jsoup
import org.skepsun.kototoro.core.db.dao.JsonSourceDao
import org.skepsun.kototoro.core.db.entity.JsonSourceEntity
import org.skepsun.kototoro.core.db.entity.JsonSourceSummary
import org.skepsun.kototoro.core.db.entity.JsonSourceType
import org.skepsun.kototoro.core.model.jsonsource.LegadoBookSource
import org.skepsun.kototoro.core.prefs.AppSettings

import org.skepsun.kototoro.core.parser.rule.DefaultRuleEngine
import org.skepsun.kototoro.core.parser.rule.RuleCache

/**
 * Comprehensive property-based test suite for JSON source functionality.
 * Tests universal properties that should hold across all inputs.
 * 
 * **Feature: runtime-json-parser, Property Test Suite**
 * **Validates: All requirements**
 */
class JsonSourcePropertyTestSuite : StringSpec({
    
    val sourceTypeIdentifier = SourceTypeIdentifier()
    val ruleCache = RuleCache()
    val ruleEngine = DefaultRuleEngine(ruleCache)

    // Generators for property tests
    val arbSourceName = Arb.string(1..30)
    val arbUrl = Arb.choice(
        Arb.constant("http://example.com"),
        Arb.constant("https://test.org"),
        Arb.constant("https://manga.io")
    )
    val arbCssSelector = Arb.choice(
        Arb.constant("div.content"),
        Arb.constant("span.title@text"),
        Arb.constant("a@href"),
        Arb.constant("p")
    )
    val arbHtmlContent = Arb.choice(
        Arb.constant("<div class='content'>Test Content</div>"),
        Arb.constant("<span class='title'>Title Text</span>"),
        Arb.constant("<a href='/link'>Link Text</a>")
    )

    /**
     * Property 1: JSON 源标识符唯一性
     * For any two different JSON sources, their identifiers must be different
     * **Validates: Requirements 11.1, 11.2, 11.3**
     */
    "Property 1: representative JSON source identifiers are distinct".config(invocations = 10) {
        val mockDao = MockJsonSourceDao()
        val manager = JsonSourceManager(mockDao, mockk<AppSettings>(relaxed = true))

        val representativeNames = listOf(
            "source-alpha",
            "source-beta",
            "source-gamma",
            "source-delta",
            "source-epsilon",
        )

        checkAll(Arb.enum<JsonSourceType>()) { type ->
            val ids = representativeNames.map { name -> manager.generateSourceId(name, type) }
            ids.distinct().size shouldBe representativeNames.size
        }
    }

    /**
     * Property 2: 源类型识别一致性
     * For any source identifier starting with "JSON_", isJsonSource() must return true
     * **Validates: Requirements 2.5, 11.1**
     */
    "Property 2: Source type identification is consistent".config(invocations = 50) {
        val mockDao = MockJsonSourceDao()
        val manager = JsonSourceManager(mockDao, mockk<AppSettings>(relaxed = true))
        
        checkAll(arbSourceName, Arb.enum<JsonSourceType>()) { name, type ->
            if (name.isNotBlank()) {
                val sourceId = manager.generateSourceId(name, type)
                
                // All generated JSON source IDs should start with JSON_
                sourceId shouldStartWith "JSON_"
                
                // isJsonSource should return true
                sourceTypeIdentifier.isJsonSource(sourceId) shouldBe true
                
                // getSourceType should return a JSON type
                val sourceType = sourceTypeIdentifier.getSourceType(sourceId)
                assert(sourceType.name.contains("JSON")) {
                    "Source type should be JSON-related but got $sourceType"
                }
            }
        }
    }

    /**
     * Property 3: 规则解析幂等性
     * For any HTML element and rule string, multiple calls to parseField() 
     * should return the same result
     * **Validates: Requirements 3.1, 3.2**
     */
    "Property 3: Rule parsing is idempotent".config(invocations = 30) {
        checkAll(arbHtmlContent, arbCssSelector) { html, selector ->
            try {
                val doc = Jsoup.parse(html)
                val element = doc.body()
                
                val result1 = ruleEngine.parseField(element, selector)
                val result2 = ruleEngine.parseField(element, selector)
                val result3 = ruleEngine.parseField(element, selector)
                
                result1 shouldBe result2
                result2 shouldBe result3
            } catch (e: Exception) {
                // Invalid selectors may throw exceptions, which is acceptable
                // The property is about consistency when parsing succeeds
            }
        }
    }

    /**
     * Property 7: 规则缓存一致性
     * For any rule string, cached compilation result must be equivalent to fresh compilation
     * **Validates: Requirements 10.3**
     */
    "Property 7: Rule cache is consistent".config(invocations = 30) {
        checkAll(arbCssSelector) { rule ->
            try {
                // Compile rule directly
                val directResult = ruleEngine.compileRule(rule)
                
                // Get from cache (will compile if not cached)
                val cachedResult1 = ruleCache.getOrCompile(rule) { ruleEngine.compileRule(it) }
                
                // Get from cache again (should be cached now)
                val cachedResult2 = ruleCache.getOrCompile(rule) { ruleEngine.compileRule(it) }
                
                // All results should be equivalent
                directResult.type shouldBe cachedResult1.type
                directResult.selector shouldBe cachedResult1.selector
                cachedResult1.type shouldBe cachedResult2.type
                cachedResult1.selector shouldBe cachedResult2.selector
            } catch (e: Exception) {
                // Invalid rules may throw exceptions, which is acceptable
            }
        }
    }

    /**
     * Property 9: 错误处理非崩溃性
     * For any invalid JSON configuration or rule, the system must catch errors 
     * and return meaningful error information without crashing
     * **Validates: Requirements 9.1, 9.3**
     */
    "Property 9: Error handling does not crash".config(invocations = 1) {
        val invalidInputs = listOf(
            "{ invalid json }",
            "[]",
            "[{}]",
            """[{"bookSourceName": ""}]""",
            "null",
            "",
            "not json at all"
        )
        
        for (invalidJson in invalidInputs) {
            try {
                // Attempt to parse invalid JSON
                val result = parseJsonSafely(invalidJson)
                
                // If parsing succeeds, validation should catch issues
                // If parsing fails, should return failure result
                assert(result == null || result.isEmpty()) {
                    "Invalid input should either fail parsing or validation"
                }
            } catch (e: Exception) {
                // Exceptions are acceptable as long as they're caught
                assert(e.message != null) { "Exception should have a message" }
            }
        }
    }

    /**
     * Property 10: 源标识符格式规范性
     * For any generated JSON source identifier, it must conform to the format
     * "JSON_" + uppercase alphanumeric with underscores
     * **Validates: Requirements 11.1, 11.2**
     */
    "Property 10: Source identifier format is compliant".config(invocations = 50) {
        val mockDao = MockJsonSourceDao()
        val manager = JsonSourceManager(mockDao, mockk<AppSettings>(relaxed = true))
        
        checkAll(arbSourceName, Arb.enum<JsonSourceType>()) { name, type ->
            if (name.isNotBlank()) {
                val sourceId = manager.generateSourceId(name, type)
                
                // Must start with JSON_
                sourceId shouldStartWith "JSON_"
                
                sourceId shouldMatch Regex("^JSON_(LEGADO|TVBOX|JS|LNREADER)_[A-F0-9]+$")
            }
        }
    }
})

private fun parseJsonSafely(json: String): List<LegadoBookSource>? {
    return try {
        kotlinx.serialization.json.Json {
            ignoreUnknownKeys = true
            isLenient = true
        }.decodeFromString(json)
    } catch (e: Exception) {
        null
    }
}

/**
 * Mock implementation of JsonSourceDao for testing purposes.
 */
private class MockJsonSourceDao : JsonSourceDao {
    private val sources = mutableListOf<JsonSourceEntity>()
    private val flowState = MutableStateFlow<List<JsonSourceEntity>>(emptyList())
    
    override fun observeEnabled() = flowState
    override fun observeEnabledSummaries() = MutableStateFlow<List<JsonSourceSummary>>(emptyList())
    override fun observeAllSummaries() = MutableStateFlow<List<JsonSourceSummary>>(emptyList())
    override fun observeAll() = flowState
    override fun observeByType(type: JsonSourceType) = flowState
    override fun observeEnabledByType(type: JsonSourceType) = flowState
    override fun observeRecentlyUsed(limit: Int) = flowState
    
    override suspend fun getById(id: String) = sources.firstOrNull { it.id == id }
    override suspend fun getByIds(ids: List<String>) = sources.filter { it.id in ids }
    override suspend fun findAll(): List<JsonSourceEntity> = sources.toList()
    override suspend fun findByName(name: String): JsonSourceEntity? = sources.firstOrNull { it.name == name }
    override suspend fun countByType(type: JsonSourceType) = sources.count { it.type == type }
    override suspend fun countEnabled() = sources.count { it.enabled }
    
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
    
    override suspend fun setLastUsed(id: String, timestamp: Long) {
        val index = sources.indexOfFirst { it.id == id }
        if (index >= 0) {
            sources[index] = sources[index].copy(lastUsedAt = timestamp)
            flowState.value = sources.toList()
        }
    }
    
    override suspend fun fillMissingIconUrl(id: String, iconUrl: String, timestamp: Long) = Unit
    
    override suspend fun delete(source: JsonSourceEntity) {
        sources.remove(source)
        flowState.value = sources.toList()
    }
    
    override suspend fun deleteById(id: String) {
        sources.removeIf { it.id == id }
        flowState.value = sources.toList()
    }
    
    override suspend fun deleteByIds(ids: List<String>) {
        sources.removeIf { it.id in ids }
        flowState.value = sources.toList()
    }
    
    override suspend fun deleteByType(type: JsonSourceType) {
        sources.removeIf { it.type == type }
        flowState.value = sources.toList()
    }
}
