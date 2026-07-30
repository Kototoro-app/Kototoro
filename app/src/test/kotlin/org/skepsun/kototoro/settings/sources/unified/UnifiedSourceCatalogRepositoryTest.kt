package org.skepsun.kototoro.settings.sources.unified

import android.content.Context
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import kotlinx.serialization.json.Json
import org.skepsun.kototoro.aniyomi.AniyomiExtensionManager
import org.skepsun.kototoro.core.db.MangaDatabase
import org.skepsun.kototoro.core.db.entity.JsonSourceEntity
import org.skepsun.kototoro.core.db.entity.JsonSourceSummary
import org.skepsun.kototoro.core.db.entity.JsonSourceType
import org.skepsun.kototoro.core.jsonsource.JsonContentSource
import org.skepsun.kototoro.core.jsonsource.JsonSourceManager
import org.skepsun.kototoro.core.prefs.AppSettings
import org.skepsun.kototoro.explore.data.ContentSourcesRepository
import org.skepsun.kototoro.extensions.repo.ExternalExtensionRepo
import org.skepsun.kototoro.extensions.repo.ExternalExtensionRepoRepository
import org.skepsun.kototoro.extensions.repo.ExternalExtensionType
import org.skepsun.kototoro.ireader.IReaderExtensionManager
import org.skepsun.kototoro.mihon.MihonExtensionManager
import org.skepsun.kototoro.cloudstream.runtime.CloudstreamRuntimeManager

class UnifiedSourceCatalogRepositoryTest : FunSpec({

    test("lnreader package ids stay unique when plugin metadata ids repeat") {
        val packages = testRepository().invokeToJsonPackageItems(
            listOf(
                lnReaderEntity(id = "JSON_LNREADER_ALPHA", name = "Alpha"),
                lnReaderEntity(id = "JSON_LNREADER_BETA", name = "Beta"),
            ),
        )

        packages.map { it.id }.distinct() shouldHaveSize 2
        packages.map { it.packageName } shouldBe listOf("JSON_LNREADER_ALPHA", "JSON_LNREADER_BETA")
        packages.map { it.sourceNames } shouldBe listOf(listOf("Alpha"), listOf("Beta"))
    }

    test("lnreader source item uses plugin metadata language instead of blank json source locale") {
        val entity = lnReaderEntity(
            id = "JSON_LNREADER_69SHU",
            name = "69书吧",
            lang = "中文, 汉语, 漢語",
        )

        val item = testRepository().invokeToUnifiedSourceItem(
            source = JsonContentSource(entity),
            jsonSummary = null,
            jsonEntity = entity,
        )

        item.language shouldBe "zh"
    }

    test("protobuf extension repository keeps its index url") {
        val repository = testRepository()
        val item = repository.invokeToUnifiedRepositoryItem(
            ExternalExtensionRepo(
                type = ExternalExtensionType.MIHON,
                baseUrl = KEIYOUSHI_PROTOBUF_URL,
                name = "Keiyoushi",
                shortName = null,
                website = "https://keiyoushi.github.io",
                signingKeyFingerprint = "fingerprint",
                createdAt = 1L,
                updatedAt = 1L,
                lastSuccessAt = 1L,
                lastError = null,
            ),
        )

        item.url shouldBe KEIYOUSHI_PROTOBUF_URL
    }

    test("preset merge replaces legacy json url and removes duplicate repository ids") {
        val repository = testRepository()
        val legacyItem = unifiedKeiyoushiRepositoryItem(
            name = "Legacy Keiyoushi",
            url = "https://github.com/keiyoushi/extensions/raw/repo/index.min.json",
        )
        val protobufItem = repository.invokeToUnifiedRepositoryItem(
            ExternalExtensionRepo(
                type = ExternalExtensionType.MIHON,
                baseUrl = KEIYOUSHI_PROTOBUF_URL,
                name = "Keiyoushi",
                shortName = null,
                website = "https://keiyoushi.github.io",
                signingKeyFingerprint = "fingerprint",
                createdAt = 2L,
                updatedAt = 2L,
                lastSuccessAt = 2L,
                lastError = null,
            ),
        )

        val matchingItems = repository.invokeWithPresetRepositories(listOf(legacyItem, protobufItem))
            .filter { it.id == KEIYOUSHI_REPOSITORY_ID }

        matchingItems shouldHaveSize 1
        matchingItems.single().url shouldBe KEIYOUSHI_PROTOBUF_URL
        matchingItems.single().isConfigured shouldBe true
        matchingItems.single().isPreset shouldBe true
    }
})

@Suppress("UNCHECKED_CAST")
private fun UnifiedSourceCatalogRepository.invokeToJsonPackageItems(
    sources: List<JsonSourceEntity>,
): List<UnifiedSourcePackageItem> {
    val method = javaClass
        .getDeclaredMethod("toJsonPackageItems", List::class.java)
    method.isAccessible = true
    return method.invoke(this, sources) as List<UnifiedSourcePackageItem>
}

private fun UnifiedSourceCatalogRepository.invokeToUnifiedSourceItem(
    source: JsonContentSource,
    jsonSummary: JsonSourceSummary?,
    jsonEntity: JsonSourceEntity?,
): UnifiedSourceItem {
    val method = javaClass.getDeclaredMethod(
        "toUnifiedSourceItem",
        org.skepsun.kototoro.parsers.model.ContentSource::class.java,
        org.skepsun.kototoro.core.db.entity.MangaSourceEntity::class.java,
        JsonSourceSummary::class.java,
        JsonSourceEntity::class.java,
    )
    method.isAccessible = true
    return method.invoke(this, source, null, jsonSummary, jsonEntity) as UnifiedSourceItem
}

private fun UnifiedSourceCatalogRepository.invokeToUnifiedRepositoryItem(
    repo: ExternalExtensionRepo,
): UnifiedSourceRepositoryItem {
    val method = javaClass.getDeclaredMethod(
        "toUnifiedRepositoryItem",
        ExternalExtensionRepo::class.java,
        Boolean::class.javaPrimitiveType,
    )
    method.isAccessible = true
    return method.invoke(this, repo, false) as UnifiedSourceRepositoryItem
}

@Suppress("UNCHECKED_CAST")
private fun UnifiedSourceCatalogRepository.invokeWithPresetRepositories(
    repositories: List<UnifiedSourceRepositoryItem>,
): List<UnifiedSourceRepositoryItem> {
    val method = javaClass.getDeclaredMethod("withPresetRepositories", List::class.java)
    method.isAccessible = true
    return method.invoke(this, repositories) as List<UnifiedSourceRepositoryItem>
}

private fun testRepository(): UnifiedSourceCatalogRepository {
    return UnifiedSourceCatalogRepository(
        appContext = mockk<Context>(relaxed = true),
        localizedContext = mockk<Context>(relaxed = true),
        database = mockk<MangaDatabase>(relaxed = true),
        settings = mockk<AppSettings>(relaxed = true),
        contentSourcesRepository = mockk<ContentSourcesRepository>(relaxed = true),
        jsonSourceManager = mockk<JsonSourceManager>(relaxed = true),
        extensionRepoRepository = mockk<ExternalExtensionRepoRepository>(relaxed = true),
        mihonExtensionManager = mockk<MihonExtensionManager>(relaxed = true),
        aniyomiExtensionManager = mockk<AniyomiExtensionManager>(relaxed = true),
        ireaderExtensionManager = mockk<IReaderExtensionManager>(relaxed = true),
        cloudstreamRuntimeManager = mockk<CloudstreamRuntimeManager>(relaxed = true),
        json = Json,
    )
}

private fun lnReaderEntity(
    id: String,
    name: String,
    lang: String = "en",
): JsonSourceEntity {
    return JsonSourceEntity(
        id = id,
        name = name,
        type = JsonSourceType.LNREADER,
        config = """
            export default {
                id: '+a+',
                name: '$name',
                site: 'https://example.org/$name',
                version: '1.0.0',
                lang: '$lang'
            }
        """.trimIndent(),
        createdAt = 1L,
        updatedAt = 1L,
    )
}

private fun unifiedKeiyoushiRepositoryItem(
    name: String,
    url: String,
): UnifiedSourceRepositoryItem {
    return UnifiedSourceRepositoryItem(
        id = KEIYOUSHI_REPOSITORY_ID,
        kind = UnifiedSourceKind.MIHON,
        name = name,
        url = url,
        locationType = UnifiedRepositoryLocationType.REMOTE_URL,
        website = "https://keiyoushi.github.io",
        isConfigured = true,
        isPreset = false,
        capabilities = emptySet(),
    )
}

private const val KEIYOUSHI_PROTOBUF_URL = "https://github.com/keiyoushi/extensions/raw/repo/index.pb"
private const val KEIYOUSHI_REPOSITORY_ID = "repo:MIHON:https://github.com/keiyoushi/extensions/raw"
