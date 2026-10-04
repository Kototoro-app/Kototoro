package org.skepsun.kototoro.source.host

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.skepsun.kototoro.core.source.SourceProtocolJson
import org.skepsun.kototoro.core.source.LoadedMihonExtension
import java.nio.file.Path

class MihonJarRegistryTest {
    @TempDir
    lateinit var directory: Path

    @Test
    fun `replacement failure retains old sources and publication happens only after persistence`() {
        val fixtures = JarFixture(directory)
        val first = fixtures.sourceJar()
        val next = fixtures.sourceJar("Updated")
        val broken = fixtures.sourceJar("Broken", body = "public Broken() { throw new IllegalStateException(\"bad\"); }")
        fixtures.compatibilityLoader().use { compatibility ->
            MihonJarRegistry(compatibility).use { registry ->
                val original = registry.load(first, fixtures.identity(first))
                assertThrows(SourceJarException::class.java) {
                    registry.replace(next, fixtures.identity(next)) { throw java.io.IOException("disk failure") }
                }
                assertEquals(original, registry.installed().single())
                assertThrows(SourceJarException::class.java) { registry.replace(broken, fixtures.identity(broken)) {} }
                assertEquals(original, registry.installed().single())
                var persisted = false
                val updated = registry.replace(next, fixtures.identity(next)) {
                    assertEquals(original, registry.installed().single())
                    persisted = true
                }
                assertTrue(persisted)
                assertEquals(updated, registry.installed().single())
                registry.withSource(updated.sources.single().source.name) { assertEquals("fixture.extension.Updated", it.javaClass.name) }
            }
        }
    }

    @Test
    fun `replacement keeps the retired loader alive for an existing suspended lease`() = kotlinx.coroutines.runBlocking<Unit> {
        val fixtures = JarFixture(directory)
        val first = fixtures.sourceJar()
        val next = fixtures.sourceJar("Updated")
        fixtures.compatibilityLoader().use { compatibility ->
            MihonJarRegistry(compatibility).use { registry ->
                val original = registry.load(first, fixtures.identity(first))
                registry.withSourceSuspending(original.sources.single().source.name) { lease ->
                    val retained = lease.retain()
                    val updated = registry.replace(next, fixtures.identity(next)) {}
                    assertEquals(updated, registry.installed().single())
                    assertEquals("fixture.extension.Single", lease.instance.javaClass.name)
                    assertTrue(lease.loader.getResource("AndroidManifest.xml") != null)
                    retained.close()
                }
                registry.withSource(original.sources.single().source.name) { assertEquals("fixture.extension.Updated", it.javaClass.name) }
            }
        }
    }

    @Test
    fun `isolated JAR entry loads and exposes exact Android source key`() {
        val fixtures = JarFixture(directory)
        val path = fixtures.sourceJar()
        fixtures.compatibilityLoader().use { compatibility ->
            MihonJarRegistry(compatibility).use { registry ->
                val loaded = registry.load(path, fixtures.identity(path))
                val source = loaded.sources.single()
                assertEquals("MIHON_9007199254740993", source.source.name)
                assertEquals(9007199254740993L, source.sourceId)
                assertEquals("漫画", source.displayName)
                assertEquals("zh", source.source.locale)
                assertEquals("MANGA", source.source.contentType)
                assertTrue(source.supportsLatest)
                registry.withSource(source.source.name) { instance ->
                    assertTrue(instance.javaClass.classLoader !== compatibility)
                    assertSame(instance.javaClass.classLoader, Thread.currentThread().contextClassLoader)
                }
                val body = SourceProtocolJson.encodeToString(loaded)
                assertTrue(body.contains("\"sourceId\":\"9007199254740993\""))
                assertEquals(loaded, SourceProtocolJson.decodeFromString<LoadedMihonExtension>(body))
            }
        }
    }

    @Test
    fun `SourceFactory expands all languages and long extrema independently`() {
        val fixtures = JarFixture(directory)
        val path = fixtures.factoryJar()
        fixtures.compatibilityLoader().use { compatibility ->
            MihonJarRegistry(compatibility).use { registry ->
                val loaded = registry.load(path, fixtures.identity(path))
                assertEquals(listOf(Long.MIN_VALUE, Long.MAX_VALUE), loaded.sources.map { it.sourceId })
                assertEquals(listOf("en", "ja"), loaded.sources.map { it.source.locale })
                assertEquals(listOf("MIHON_${Long.MIN_VALUE}", "MIHON_${Long.MAX_VALUE}"),
                    loaded.sources.map { it.source.name })
            }
        }
    }

    @Test
    fun `identity check prevents executing malicious constructor`() {
        val fixtures = JarFixture(directory)
        val path = fixtures.sourceJar(body = "public Single() { throw new AssertionError(\"executed\"); }")
        fixtures.compatibilityLoader().use { compatibility ->
            MihonJarRegistry(compatibility).use { registry ->
                assertEquals(SourceJarFailure.HASH_MISMATCH, assertThrows(SourceJarException::class.java) {
                    registry.load(path, fixtures.identity(path).copy(sha256 = "0".repeat(64)))
                }.failure)
                assertTrue(registry.installed().isEmpty())
            }
        }
    }

    @Test
    fun `duplicate source IDs in factory cannot partially publish extension`() {
        val fixtures = JarFixture(directory)
        val path = fixtures.factoryJar(duplicate = true)
        fixtures.compatibilityLoader().use { compatibility ->
            MihonJarRegistry(compatibility).use { registry ->
                assertEquals(SourceJarFailure.DUPLICATE_SOURCE, assertThrows(SourceJarException::class.java) {
                    registry.load(path, fixtures.identity(path))
                }.failure)
                assertTrue(registry.installed().isEmpty())
            }
        }
    }

    @Test
    fun `source collisions across packages preserve first registered extension`() {
        val fixtures = JarFixture(directory)
        val first = fixtures.sourceJar()
        val second = fixtures.sourceJar("Other", packageName = "fixture.other")
        fixtures.compatibilityLoader().use { compatibility ->
            MihonJarRegistry(compatibility).use { registry ->
                val original = registry.load(first, fixtures.identity(first))
                assertEquals(SourceJarFailure.DUPLICATE_SOURCE, assertThrows(SourceJarException::class.java) {
                    registry.load(second, fixtures.identity(second))
                }.failure)
                assertEquals(listOf(original), registry.installed())
            }
        }
    }

    @Test
    fun `empty and throwing factories do not leave registrations or thread loader changes`() {
        val fixtures = JarFixture(directory)
        val previous = Thread.currentThread().contextClassLoader
        fixtures.compatibilityLoader().use { compatibility ->
            MihonJarRegistry(compatibility).use { registry ->
                val empty = fixtures.factoryJar(empty = true)
                assertEquals(SourceJarFailure.NO_SOURCES, assertThrows(SourceJarException::class.java) {
                    registry.load(empty, fixtures.identity(empty))
                }.failure)
                val throwing = fixtures.factoryJar(throwing = true)
                assertEquals(SourceJarFailure.CONSTRUCTION_FAILED, assertThrows(SourceJarException::class.java) {
                    registry.load(throwing, fixtures.identity(throwing))
                }.failure)
                assertTrue(registry.installed().isEmpty())
                assertSame(previous, Thread.currentThread().contextClassLoader)
            }
        }
    }

    @Test
    fun `unload closes handles permits reload and does not delete artifact`() {
        val fixtures = JarFixture(directory)
        val path = fixtures.sourceJar()
        fixtures.compatibilityLoader().use { compatibility ->
            MihonJarRegistry(compatibility).use { registry ->
                registry.load(path, fixtures.identity(path))
                assertTrue(registry.unload("fixture.extension"))
                assertFalse(registry.unload("fixture.extension"))
                assertTrue(java.nio.file.Files.exists(path))
                assertTrue(registry.installed().isEmpty())
                registry.load(path, fixtures.identity(path))
                assertEquals(1, registry.installed().size)
            }
        }
    }

    @Test
    fun `entry point cannot alias a class owned by compatibility loader`() {
        val fixtures = JarFixture(directory)
        val path = fixtures.sourceJar(metadata = JarFixture.manifest(
            entry = "eu.kanade.tachiyomi.source.CatalogueSource",
        ))
        fixtures.compatibilityLoader().use { compatibility ->
            MihonJarRegistry(compatibility).use { registry ->
                assertEquals(SourceJarFailure.INVALID_ARCHIVE, assertThrows(SourceJarException::class.java) {
                    registry.load(path, fixtures.identity(path))
                }.failure)
                assertTrue(registry.installed().isEmpty())
            }
        }
    }

    @Test
    fun `duplicate package close and missing compatibility API are explicit failures`() {
        val fixtures = JarFixture(directory)
        val path = fixtures.sourceJar()
        fixtures.compatibilityLoader().use { compatibility ->
            val registry = MihonJarRegistry(compatibility)
            registry.load(path, fixtures.identity(path))
            assertEquals(SourceJarFailure.ALREADY_LOADED, assertThrows(SourceJarException::class.java) {
                registry.load(path, fixtures.identity(path))
            }.failure)
            registry.close()
            registry.close()
            assertEquals(SourceJarFailure.CLOSED, assertThrows(SourceJarException::class.java) {
                registry.installed()
            }.failure)
        }
        MihonJarRegistry(javaClass.classLoader).use { registry ->
            assertEquals(SourceJarFailure.API_UNAVAILABLE, assertThrows(SourceJarException::class.java) {
                registry.load(path, fixtures.identity(path))
            }.failure)
        }
    }
}
