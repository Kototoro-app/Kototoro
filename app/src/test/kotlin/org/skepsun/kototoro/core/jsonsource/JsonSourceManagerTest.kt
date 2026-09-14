package org.skepsun.kototoro.core.jsonsource

import io.mockk.mockk
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.core.db.dao.JsonSourceDao
import org.skepsun.kototoro.core.db.entity.JsonSourceEntity
import org.skepsun.kototoro.core.db.entity.JsonSourceSummary
import org.skepsun.kototoro.core.db.entity.JsonSourceType
import org.skepsun.kototoro.core.prefs.AppSettings

/**
 * Fake DAO implementation for testing without database
 */
class FakeJsonSourceDao : JsonSourceDao {
	private val sources = mutableListOf<JsonSourceEntity>()
	
	override fun observeEnabled(): Flow<List<JsonSourceEntity>> = flowOf(sources.filter { it.enabled })
	override fun observeEnabledSummaries(): Flow<List<JsonSourceSummary>> = flowOf(emptyList())
	override fun observeAllSummaries(): Flow<List<JsonSourceSummary>> = flowOf(emptyList())
	override fun observeAll(): Flow<List<JsonSourceEntity>> = flowOf(sources)
	override fun observeByType(type: JsonSourceType): Flow<List<JsonSourceEntity>> = flowOf(sources.filter { it.type == type })
	override fun observeEnabledByType(type: JsonSourceType): Flow<List<JsonSourceEntity>> = flowOf(sources.filter { it.enabled && it.type == type })
	override fun observeRecentlyUsed(limit: Int): Flow<List<JsonSourceEntity>> = flowOf(sources.filter { it.enabled && it.lastUsedAt > 0 }.sortedByDescending { it.lastUsedAt }.take(limit))
	override suspend fun getById(id: String): JsonSourceEntity? = sources.find { it.id == id }
	override suspend fun getByIds(ids: List<String>): List<JsonSourceEntity> = sources.filter { it.id in ids }
	override suspend fun findAll(): List<JsonSourceEntity> = sources.toList()
	override suspend fun findByName(name: String): JsonSourceEntity? = sources.find { it.name == name }
	override suspend fun countByType(type: JsonSourceType): Int = sources.count { it.type == type }
	override suspend fun countEnabled(): Int = sources.count { it.enabled }
	override suspend fun insert(source: JsonSourceEntity) { sources.add(source) }
	override suspend fun insertAll(sources: List<JsonSourceEntity>) { this.sources.addAll(sources) }
	override suspend fun update(source: JsonSourceEntity) {
		sources.indexOfFirst { it.id == source.id }
			.takeIf { it >= 0 }
			?.let { index -> sources[index] = source }
	}
	override suspend fun setEnabled(id: String, enabled: Boolean, timestamp: Long) {
		sources.find { it.id == id }?.let {
			sources[sources.indexOf(it)] = it.copy(enabled = enabled, updatedAt = timestamp)
		}
	}
	override suspend fun setEnabledBatch(ids: List<String>, enabled: Boolean, timestamp: Long) {
		ids.forEach { id ->
			sources.find { it.id == id }?.let {
				sources[sources.indexOf(it)] = it.copy(enabled = enabled, updatedAt = timestamp)
			}
		}
	}
	override suspend fun setPinned(id: String, isPinned: Boolean, timestamp: Long) = Unit
	override suspend fun setPinnedBatch(ids: List<String>, isPinned: Boolean, timestamp: Long) = Unit
	override suspend fun setLastUsed(id: String, timestamp: Long) {
		sources.find { it.id == id }?.let {
			sources[sources.indexOf(it)] = it.copy(lastUsedAt = timestamp)
		}
	}
	override suspend fun fillMissingIconUrl(id: String, iconUrl: String, timestamp: Long) = Unit
	override suspend fun delete(source: JsonSourceEntity) { sources.remove(source) }
	override suspend fun deleteById(id: String) { sources.removeIf { it.id == id } }
	override suspend fun deleteByIds(ids: List<String>) { sources.removeIf { it.id in ids } }
	override suspend fun deleteByType(type: JsonSourceType) { sources.removeIf { it.type == type } }
}

/**
 * Unit tests for JsonSourceManager
 * 
 * Tests JSON validation and configuration validation logic
 */
class JsonSourceManagerTest {
	
	private lateinit var jsonSourceManager: JsonSourceManager
	
	@BeforeEach
	fun setup() {
		jsonSourceManager = JsonSourceManager(
			jsonSourceDao = FakeJsonSourceDao(),
			appSettings = mockk<AppSettings>(relaxed = true),
		)
	}
	
	// Validation Tests
	
	@Test
	fun testValidateLegadoSourceValid() {
		val validSource = org.skepsun.kototoro.core.model.jsonsource.LegadoBookSource(
			bookSourceName = "Test Source",
			bookSourceUrl = "https://example.com",
		)
		
		val result = jsonSourceManager.validateLegadoSource(validSource)
		
		assertTrue(result.isValid)
		assertTrue(result.errors.isEmpty())
	}
	
	@Test
	fun testValidateLegadoSourceBlankName() {
		val invalidSource = org.skepsun.kototoro.core.model.jsonsource.LegadoBookSource(
			bookSourceName = "",
			bookSourceUrl = "https://example.com",
		)
		
		val result = jsonSourceManager.validateLegadoSource(invalidSource)
		
		assertFalse(result.isValid)
		assertTrue(result.errors.any { it.contains("bookSourceName") })
	}
	
	@Test
	fun testValidateLegadoSourceBlankUrl() {
		val source = org.skepsun.kototoro.core.model.jsonsource.LegadoBookSource(
			bookSourceName = "Test Source",
			bookSourceUrl = "",
		)
		
		val result = jsonSourceManager.validateLegadoSource(source)
		
		assertTrue(result.isValid)
		assertTrue(result.errors.isEmpty())
	}
	
	@Test
	fun testValidateLegadoSourceInvalidUrl() {
		val source = org.skepsun.kototoro.core.model.jsonsource.LegadoBookSource(
			bookSourceName = "Test Source",
			bookSourceUrl = "ftp://example.com", // Invalid protocol
		)
		
		val result = jsonSourceManager.validateLegadoSource(source)
		
		assertTrue(result.isValid)
		assertTrue(result.errors.isEmpty())
	}
	
	@Test
	fun testValidateLegadoSourceMultipleErrors() {
		val invalidSource = org.skepsun.kototoro.core.model.jsonsource.LegadoBookSource(
			bookSourceName = "",
			bookSourceUrl = "",
		)
		
		val result = jsonSourceManager.validateLegadoSource(invalidSource)
		
		assertFalse(result.isValid)
		assertEquals(1, result.errors.size)
		assertTrue(result.errors.any { it.contains("bookSourceName") })
	}
	
	@Test
	fun testValidateLegadoSourceHttpUrl() {
		val validSource = org.skepsun.kototoro.core.model.jsonsource.LegadoBookSource(
			bookSourceName = "Test Source",
			bookSourceUrl = "http://example.com", // HTTP is valid
		)
		
		val result = jsonSourceManager.validateLegadoSource(validSource)
		
		assertTrue(result.isValid)
	}
	
	@Test
	fun testValidateLegadoSourceHttpsUrl() {
		val validSource = org.skepsun.kototoro.core.model.jsonsource.LegadoBookSource(
			bookSourceName = "Test Source",
			bookSourceUrl = "https://example.com", // HTTPS is valid
		)
		
		val result = jsonSourceManager.validateLegadoSource(validSource)
		
		assertTrue(result.isValid)
	}
	
	@Test
	fun testValidateLegadoSourceWithPath() {
		val validSource = org.skepsun.kototoro.core.model.jsonsource.LegadoBookSource(
			bookSourceName = "Test Source",
			bookSourceUrl = "https://example.com/api/books",
		)
		
		val result = jsonSourceManager.validateLegadoSource(validSource)
		
		assertTrue(result.isValid)
	}
	
	@Test
	fun testValidateLegadoSourceWithQueryParams() {
		val validSource = org.skepsun.kototoro.core.model.jsonsource.LegadoBookSource(
			bookSourceName = "Test Source",
			bookSourceUrl = "https://example.com/search?q=test",
		)
		
		val result = jsonSourceManager.validateLegadoSource(validSource)
		
		assertTrue(result.isValid)
	}
	
	@Test
	fun testValidateLegadoSourceInvalidUrlFormat() {
		val source = org.skepsun.kototoro.core.model.jsonsource.LegadoBookSource(
			bookSourceName = "Test Source",
			bookSourceUrl = "not-a-valid-url",
		)
		
		val result = jsonSourceManager.validateLegadoSource(source)
		
		assertTrue(result.isValid)
		assertTrue(result.errors.isEmpty())
	}
	
	// ValidationResult Tests
	
	@Test
	fun testValidationResultSuccess() {
		val result = ValidationResult.success()
		
		assertTrue(result.isValid)
		assertEquals(0, result.errors.size)
	}
	
	@Test
	fun testValidationResultFailureWithVarargs() {
		val result = ValidationResult.failure("Error 1", "Error 2")
		
		assertFalse(result.isValid)
		assertEquals(2, result.errors.size)
		assertTrue(result.errors.contains("Error 1"))
		assertTrue(result.errors.contains("Error 2"))
	}
	
	@Test
	fun testValidationResultFailureWithList() {
		val errors = listOf("Error 1", "Error 2", "Error 3")
		val result = ValidationResult.failure(errors)
		
		assertFalse(result.isValid)
		assertEquals(3, result.errors.size)
	}
}
