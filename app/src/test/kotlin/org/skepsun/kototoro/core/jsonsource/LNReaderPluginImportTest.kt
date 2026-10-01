package org.skepsun.kototoro.core.jsonsource

import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.core.db.dao.JsonSourceDao
import org.skepsun.kototoro.core.db.entity.JsonSourceEntity
import org.skepsun.kototoro.core.lnreader.LNReaderPluginMetadata
import org.skepsun.kototoro.core.prefs.AppSettings
import org.skepsun.kototoro.settings.sources.unified.isNewerLnReaderVersion

class LNReaderPluginImportTest {

    @Test
    fun `metadata comment safely preserves unicode names and replaces previous metadata`() {
        val code = "exports.default = class Plugin { constructor() { this.version = '1.0.0'; } };"
        val original = LNReaderPluginMetadata("test", "测试 */\nsource", version = "1.1.10")
        val updated = original.copy(version = "1.1.11")
        val wrapped = updated.withMetadataHeader(original.withMetadataHeader(code))

        LNReaderPluginMetadata.extractFromCode(wrapped, "fallback") shouldBe updated
        wrapped.substringAfter('\n') shouldBe code
    }

    @Test
    fun `invalid metadata comment falls back to the plugin code`() {
        val code = "// kototoro-lnreader-metadata:invalid\nthis.id = 'test'; this.version = '1.2.3';"

        LNReaderPluginMetadata.extractFromCode(code, "fallback")?.version shouldBe "1.2.3"
    }

    @Test
    fun `catalog version survives import when plugin computes version at runtime`() = runTest {
        // LightNovelWP bundles compute the final version rather than storing a string literal.
        val code = """
            class Base {
                constructor() { this.version = "1.1.".concat(10); }
            }
            class Plugin extends Base {
                constructor() {
                    super();
                    this.id = "bacalightnovel";
                    this.name = "Baca Light Novel";
                    this.site = "https://bacalightnovel.co/";
                }
            }
        """.trimIndent()
        val catalogMetadata = LNReaderPluginMetadata(
            id = "bacalightnovel",
            name = "Baca Light Novel",
            site = "https://bacalightnovel.co/",
            version = "1.1.10",
        )
        val stored = slot<JsonSourceEntity>()
        val dao = mockk<JsonSourceDao>()
        coEvery { dao.getById(any()) } returns null
        coEvery { dao.insert(capture(stored)) } returns Unit
        val manager = JsonSourceManager(dao, mockk<AppSettings>(relaxed = true))

        manager.importLNReaderPlugin(code, catalogMetadata).getOrThrow() shouldBe 1
        val installed = requireNotNull(LNReaderPluginMetadata.extractFromCode(stored.captured.config, "fallback"))

        installed.version shouldBe "1.1.10"
        installed.site shouldBe catalogMetadata.site
        stored.captured.config.endsWith(code) shouldBe true
        isNewerLnReaderVersion("1.1.10", installed.version) shouldBe false
        isNewerLnReaderVersion("1.1.11", installed.version) shouldBe true
    }
}
