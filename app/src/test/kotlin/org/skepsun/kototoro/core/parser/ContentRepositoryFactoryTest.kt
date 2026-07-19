package org.skepsun.kototoro.core.parser

import eu.kanade.tachiyomi.source.CatalogueSource
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.core.db.dao.JsonSourceDao
import org.skepsun.kototoro.core.db.entity.JsonSourceEntity
import org.skepsun.kototoro.core.db.entity.JsonSourceSummary
import org.skepsun.kototoro.core.db.entity.JsonSourceType
import org.skepsun.kototoro.core.jsonsource.JsonContentSource
import org.skepsun.kototoro.core.jsonsource.JsonSourceManager
import org.skepsun.kototoro.core.model.ContentSourceInfo
import org.skepsun.kototoro.core.model.LocalMangaSource
import org.skepsun.kototoro.core.model.LocalNovelSource
import org.skepsun.kototoro.core.model.UnknownContentSource
import org.skepsun.kototoro.core.parser.external.ExternalContentSource
import org.skepsun.kototoro.core.prefs.AppSettings
import org.skepsun.kototoro.local.data.LocalMangaRepository
import org.skepsun.kototoro.mihon.MihonExtensionManager
import org.skepsun.kototoro.mihon.model.MihonMangaSource
import org.skepsun.kototoro.parsers.model.Content
import org.skepsun.kototoro.parsers.model.ContentChapter
import org.skepsun.kototoro.parsers.model.ContentListFilter
import org.skepsun.kototoro.parsers.model.ContentListFilterCapabilities
import org.skepsun.kototoro.parsers.model.ContentListFilterOptions
import org.skepsun.kototoro.parsers.model.ContentPage
import org.skepsun.kototoro.parsers.model.ContentSource
import org.skepsun.kototoro.parsers.model.ContentType
import org.skepsun.kototoro.parsers.model.NovelChapterContent
import org.skepsun.kototoro.parsers.model.SortOrder
import org.skepsun.kototoro.aniyomi.AniyomiExtensionManager
import org.skepsun.kototoro.aniyomi.model.AniyomiAnimeSource
import org.skepsun.kototoro.cloudstream.runtime.CloudstreamContentRepositoryProvider
import org.skepsun.kototoro.ireader.IReaderExtensionManager
import org.skepsun.kototoro.tracking.discovery.data.TrackingContentRepositoryProvider

class ContentRepositoryFactoryTest {

	private val context = mockk<android.content.Context>(relaxed = true)
	private val localContentRepository = mockk<LocalMangaRepository>(relaxed = true)
	private val localNovelRepository = mockk<org.skepsun.kototoro.local.novel.LocalNovelRepository>(relaxed = true)
	private val contentSourceInfoResolver = mockk<ContentSourceInfoResolver>()
	private val jsonContentSourceResolver = mockk<JsonContentSourceResolver>()
	private val mihonContentSourceResolver = mockk<MihonContentSourceResolver>()
	private val aniyomiContentSourceResolver = mockk<AniyomiContentSourceResolver>()
	private val ireaderContentSourceResolver = mockk<IReaderContentSourceResolver>()
	private val parserContentRepositoryProvider = mockk<ParserContentRepositoryProvider>()
	private val kotatsuContentRepositoryProvider = mockk<KotatsuContentRepositoryProvider>()
	private val testContentRepositoryProvider = mockk<TestContentRepositoryProvider>()
	private val externalContentRepositoryProvider = mockk<ExternalContentRepositoryProvider>()
	private val cloudstreamContentRepositoryProvider = mockk<CloudstreamContentRepositoryProvider>()
	private val mihonContentRepositoryProvider = mockk<MihonContentRepositoryProvider>()
	private val aniyomiContentRepositoryProvider = mockk<AniyomiContentRepositoryProvider>()
	private val ireaderContentRepositoryProvider = mockk<IReaderContentRepositoryProvider>()
	private val jsonContentRepositoryProvider = mockk<JsonContentRepositoryProvider>()
	private val trackingContentRepositoryProvider = mockk<TrackingContentRepositoryProvider>()

	private lateinit var sourceResolutionPipeline: ContentSourceResolutionPipeline
	private lateinit var repositoryProviderRegistry: ContentRepositoryProviderRegistry
	private lateinit var factory: ContentRepository.Factory

	@BeforeEach
	fun setUp() {
		listOf(
			contentSourceInfoResolver,
			jsonContentSourceResolver,
			mihonContentSourceResolver,
			aniyomiContentSourceResolver,
			ireaderContentSourceResolver,
		).forEach { resolver ->
			every { resolver.supports(any()) } returns false
			every { resolver.resolve(any()) } returns null
		}
		listOf(
			parserContentRepositoryProvider,
			kotatsuContentRepositoryProvider,
			testContentRepositoryProvider,
			externalContentRepositoryProvider,
			cloudstreamContentRepositoryProvider,
			mihonContentRepositoryProvider,
			aniyomiContentRepositoryProvider,
			ireaderContentRepositoryProvider,
			jsonContentRepositoryProvider,
			trackingContentRepositoryProvider,
		).forEach { provider ->
			every { provider.supports(any()) } returns false
			every { provider.create(any()) } returns null
		}
		sourceResolutionPipeline = ContentSourceResolutionPipeline(
			contentSourceInfoResolver = contentSourceInfoResolver,
			jsonContentSourceResolver = jsonContentSourceResolver,
			mihonContentSourceResolver = mihonContentSourceResolver,
			aniyomiContentSourceResolver = aniyomiContentSourceResolver,
			ireaderContentSourceResolver = ireaderContentSourceResolver,
		)
		repositoryProviderRegistry = ContentRepositoryProviderRegistry(
			builtinContentRepositoryProvider = BuiltinContentRepositoryProvider(
				localMangaRepository = localContentRepository,
				localNovelRepository = localNovelRepository,
			),
			parserContentRepositoryProvider = parserContentRepositoryProvider,
			kotatsuContentRepositoryProvider = kotatsuContentRepositoryProvider,
			testContentRepositoryProvider = testContentRepositoryProvider,
			externalContentRepositoryProvider = externalContentRepositoryProvider,
			cloudstreamContentRepositoryProvider = cloudstreamContentRepositoryProvider,
			mihonContentRepositoryProvider = mihonContentRepositoryProvider,
			aniyomiContentRepositoryProvider = aniyomiContentRepositoryProvider,
			ireaderContentRepositoryProvider = ireaderContentRepositoryProvider,
			jsonContentRepositoryProvider = jsonContentRepositoryProvider,
			trackingContentRepositoryProvider = trackingContentRepositoryProvider,
		)
		factory = ContentRepository.Factory(
			delegate = ContentRepositoryFactory(
			sourceResolutionPipeline = sourceResolutionPipeline,
			repositoryProviderRegistry = repositoryProviderRegistry,
			repositoryInstanceCache = ContentRepositoryInstanceCache(),
			),
		)
	}

	@Test
	fun `source info resolver unwraps nested source`() {
		val inner = namedSource("REAL")
		val wrapped = ContentSourceInfo(inner, isEnabled = true, isPinned = false)

		assertSame(inner, ContentSourceInfoResolver().resolve(wrapped))
	}

	@Test
	fun `json source resolver resolves stored source`() = runTest {
		val entity = jsonEntity(id = "JSON_JS_TEST", type = JsonSourceType.JS)
		val manager = JsonSourceManager(
			jsonSourceDao = FakeJsonSourceDao(listOf(entity)),
			appSettings = mockk<AppSettings>(relaxed = true),
		)
		val resolver = JsonContentSourceResolver(manager)

		val resolved = resolver.resolve(namedSource(entity.id))

		assertTrue(resolved is JsonContentSource)
		assertSame(entity.id, resolved?.name)
	}

	@Test
	fun `mihon source resolver resolves prefixed source`() {
		val manager = mockk<MihonExtensionManager>()
		val resolvedSource = mockk<MihonMangaSource>()
		every { manager.getMihonMangaSourceByName("MIHON_42") } returns resolvedSource

		val resolved = MihonContentSourceResolver(manager).resolve(namedSource("MIHON_42"))

		assertSame(resolvedSource, resolved)
	}

	@Test
	fun `mihon source resolver resolves persisted display name`() {
		val manager = mockk<MihonExtensionManager>()
		val resolvedSource = mihonSource(id = 42L, name = "Entity Graph")
		every { manager.getMihonMangaSources() } returns listOf(resolvedSource)

		val resolved = MihonContentSourceResolver(manager).resolve(namedSource("Entity Graph"))

		assertSame(resolvedSource, resolved)
	}

	@Test
	fun `mihon source resolver ignores ambiguous display name`() {
		val manager = mockk<MihonExtensionManager>()
		every { manager.getMihonMangaSources() } returns listOf(
			mihonSource(id = 42L, name = "Duplicated"),
			mihonSource(id = 43L, name = "Duplicated"),
		)

		val resolved = MihonContentSourceResolver(manager).resolve(namedSource("Duplicated"))

		assertSame(null, resolved)
	}

	@Test
	fun `aniyomi source resolver resolves prefixed source`() {
		val manager = mockk<AniyomiExtensionManager>()
		val resolvedSource = mockk<AniyomiAnimeSource>()
		every { manager.getAniyomiAnimeSourceByName("ANIYOMI_7") } returns resolvedSource

		val resolved = AniyomiContentSourceResolver(manager).resolve(namedSource("ANIYOMI_7"))

		assertSame(resolvedSource, resolved)
	}

	@Test
	fun `resolution pipeline resolves installed but disabled aniyomi source`() {
		val source = namedSource("ANIYOMI_7")
		val resolvedSource = mockk<AniyomiAnimeSource>(relaxed = true)
		every { aniyomiContentSourceResolver.supports(source) } returns true
		every { aniyomiContentSourceResolver.resolve(source) } returns resolvedSource
		every { aniyomiContentSourceResolver.supports(resolvedSource) } returns false

		assertSame(resolvedSource, sourceResolutionPipeline.resolve(source))
	}

	@Test
	fun `factory caches by resolved source`() {
		val shellA = namedSource("SHELL_A")
		val shellB = namedSource("SHELL_B")
		val resolved = namedSource("REAL")
		val repository = FakeRepository(resolved)

		every { contentSourceInfoResolver.supports(shellA) } returns true
		every { contentSourceInfoResolver.supports(shellB) } returns true
		every { contentSourceInfoResolver.supports(resolved) } returns false
		every { contentSourceInfoResolver.resolve(shellA) } returns resolved
		every { contentSourceInfoResolver.resolve(shellB) } returns resolved
		every { contentSourceInfoResolver.resolve(resolved) } returns null
		every { parserContentRepositoryProvider.supports(resolved) } returns true
		every { parserContentRepositoryProvider.create(resolved) } returns repository

		val first = factory.create(shellA)
		val second = factory.create(shellB)

		assertSame(repository, first)
		assertSame(repository, second)
		verify(exactly = 1) { parserContentRepositoryProvider.create(resolved) }
	}

	@Test
	fun `pipeline skips unsupported resolvers`() {
		val source = namedSource("PLAIN")

		factory.create(source)

		verify(exactly = 0) { jsonContentSourceResolver.resolve(any()) }
		verify(exactly = 0) { mihonContentSourceResolver.resolve(any()) }
		verify(exactly = 0) { aniyomiContentSourceResolver.resolve(any()) }
	}

	@Test
	fun `factory routes local manga source through builtin provider`() {
		val repository = factory.create(LocalMangaSource)

		assertSame(localContentRepository, repository)
	}

	@Test
	fun `factory routes local novel source through builtin provider`() {
		val repository = factory.create(LocalNovelSource)

		assertSame(localNovelRepository, repository)
	}

	@Test
	fun `factory falls back to empty repository for unknown source`() {
		val repository = factory.create(UnknownContentSource)

		assertTrue(repository is EmptyContentRepository)
		assertSame(UnknownContentSource, repository.source)
	}

	@Test
	fun `factory does not cache empty repository when providers do not match`() {
		val source = namedSource("UNMATCHED")

		val first = factory.create(source)
		val second = factory.create(source)

		assertTrue(first is EmptyContentRepository)
		assertTrue(second is EmptyContentRepository)
		assertSame(source, first.source)
		assertNotSame(first, second)
		verify(exactly = 0) { parserContentRepositoryProvider.create(source) }
	}

	@Test
	fun `factory diagnostics report no provider match for unmatched source`() {
		val source = namedSource("UNMATCHED_DIAGNOSTIC")

		val result = factory.createWithDiagnostics(source)

		assertTrue(result.repository is EmptyContentRepository)
		assertSame(source, result.resolvedSource)
		assertSame(ContentRepositoryFactory.FailureReason.NO_SUPPORTED_PROVIDER, result.failureReason)
		assertSame(ContentRepositoryFactory.ProviderStatus.FALLBACK_EMPTY, result.providerStatus)
		assertSame(ContentRepositoryFactory.CacheStatus.MISS, result.cacheStatus)
	}

	@Test
	fun `factory diagnostics report unknown source for unknown content source`() {
		val result = factory.createWithDiagnostics(UnknownContentSource)

		assertTrue(result.repository is EmptyContentRepository)
		assertSame(ContentRepositoryFactory.FailureReason.UNKNOWN_SOURCE, result.failureReason)
		assertSame(ContentRepositoryFactory.ProviderStatus.FALLBACK_EMPTY, result.providerStatus)
	}

	@Test
	fun `factory diagnostics report unavailable external source`() {
		val source = ExternalContentSource(packageName = "pkg.test", authority = "auth.test")
		every { externalContentRepositoryProvider.supports(source) } returns true
		every { externalContentRepositoryProvider.create(source) } returns EmptyContentRepository(source)

		val result = factory.createWithDiagnostics(source)

		assertTrue(result.repository is EmptyContentRepository)
		assertSame(ContentRepositoryFactory.FailureReason.UNAVAILABLE_EXTERNAL_SOURCE, result.failureReason)
		assertSame(ContentRepositoryFactory.ProviderStatus.FALLBACK_EMPTY, result.providerStatus)
	}

	@Test
	fun `factory diagnostics report no provider produced repository when candidate returns null`() {
		val source = namedSource("CANDIDATE_NULL")
		every { parserContentRepositoryProvider.supports(source) } returns true
		every { parserContentRepositoryProvider.create(source) } returns null

		val result = factory.createWithDiagnostics(source)

		assertTrue(result.repository is EmptyContentRepository)
		assertSame(ContentRepositoryFactory.FailureReason.NO_PROVIDER_PRODUCED_REPOSITORY, result.failureReason)
		assertEquals(listOf("ParserContentRepositoryProvider"), result.candidateProviders)
		assertEquals(listOf("ParserContentRepositoryProvider"), result.attemptedProviders)
	}

	@Test
	fun `factory diagnostics keep resolution trace and cache status`() {
		val shell = namedSource("SHELL_TRACE")
		val resolved = namedSource("REAL_TRACE")
		val repository = FakeRepository(resolved)
		every { contentSourceInfoResolver.supports(shell) } returns true
		every { contentSourceInfoResolver.supports(resolved) } returns false
		every { contentSourceInfoResolver.resolve(shell) } returns resolved
		every { parserContentRepositoryProvider.supports(resolved) } returns true
		every { parserContentRepositoryProvider.create(resolved) } returns repository

		val first = factory.createWithDiagnostics(shell)
		val second = factory.createWithDiagnostics(shell)

		assertTrue(first.resolutionTrace.isNotEmpty())
		assertSame(ContentRepositoryFactory.ResolutionStatus.RESOLVED, first.resolutionStatus)
		assertSame(ContentRepositoryFactory.ProviderStatus.SELECTED, first.providerStatus)
		assertTrue(!first.cacheHit)
		assertSame(ContentRepositoryFactory.CacheStatus.MISS, first.cacheStatus)
		assertTrue(second.cacheHit)
		assertSame(ContentRepositoryFactory.ProviderStatus.SKIPPED_BY_CACHE, second.providerStatus)
		assertSame(ContentRepositoryFactory.CacheStatus.HIT, second.cacheStatus)
		assertEquals("ContentSourceInfoResolver", first.resolutionTrace.first().resolver)
	}

	private fun namedSource(name: String): ContentSource = object : ContentSource {
		override val name: String = name
		override val locale: String = "en"
		override val contentType: ContentType = ContentType.MANGA
	}

	private fun mihonSource(id: Long, name: String): MihonMangaSource {
		val catalogueSource = mockk<CatalogueSource>()
		every { catalogueSource.id } returns id
		every { catalogueSource.name } returns name
		every { catalogueSource.lang } returns "en"
		every { catalogueSource.supportsLatest } returns false
		return MihonMangaSource(
			catalogueSource = catalogueSource,
			pkgName = "tachiyomi.extension.test.$id",
		)
	}

	private fun jsonEntity(id: String, type: JsonSourceType) = JsonSourceEntity(
		id = id,
		name = id,
		type = type,
		config = "{}",
		createdAt = 1L,
		updatedAt = 1L,
	)

	private class FakeJsonSourceDao(
		private val sources: List<JsonSourceEntity>,
	) : JsonSourceDao {
		override fun observeEnabled(): Flow<List<JsonSourceEntity>> = throw UnsupportedOperationException()
		override fun observeEnabledSummaries(): Flow<List<JsonSourceSummary>> = throw UnsupportedOperationException()
		override fun observeAllSummaries(): Flow<List<JsonSourceSummary>> = throw UnsupportedOperationException()
		override fun observeAll(): Flow<List<JsonSourceEntity>> = throw UnsupportedOperationException()
		override fun observeByType(type: JsonSourceType): Flow<List<JsonSourceEntity>> = throw UnsupportedOperationException()
		override fun observeEnabledByType(type: JsonSourceType): Flow<List<JsonSourceEntity>> = throw UnsupportedOperationException()
		override fun observeRecentlyUsed(limit: Int): Flow<List<JsonSourceEntity>> = throw UnsupportedOperationException()
		override suspend fun getById(id: String): JsonSourceEntity? = sources.find { it.id == id }
		override suspend fun getByIds(ids: List<String>): List<JsonSourceEntity> = sources.filter { it.id in ids }
		override suspend fun countByType(type: JsonSourceType): Int = sources.count { it.type == type }
		override suspend fun countEnabled(): Int = sources.count { it.enabled }
		override suspend fun insert(source: JsonSourceEntity) = Unit
		override suspend fun insertAll(sources: List<JsonSourceEntity>) = Unit
		override suspend fun update(source: JsonSourceEntity) = Unit
		override suspend fun setEnabled(id: String, enabled: Boolean, timestamp: Long) = Unit
		override suspend fun setEnabledBatch(ids: List<String>, enabled: Boolean, timestamp: Long) = Unit
		override suspend fun setPinned(id: String, isPinned: Boolean, timestamp: Long) = Unit
		override suspend fun setPinnedBatch(ids: List<String>, isPinned: Boolean, timestamp: Long) = Unit
		override suspend fun setLastUsed(id: String, timestamp: Long) = Unit
		override suspend fun fillMissingIconUrl(id: String, iconUrl: String, timestamp: Long) = Unit
		override suspend fun delete(source: JsonSourceEntity) = Unit
		override suspend fun deleteById(id: String) = Unit
		override suspend fun deleteByIds(ids: List<String>) = Unit
		override suspend fun deleteByType(type: JsonSourceType) = Unit
	}

	private class FakeRepository(
		override val source: ContentSource,
	) : ContentRepository {
		override val sortOrders: Set<SortOrder> = emptySet()
		override var defaultSortOrder: SortOrder = SortOrder.NEWEST
		override val filterCapabilities: ContentListFilterCapabilities = ContentListFilterCapabilities()
		override suspend fun getList(offset: Int, order: SortOrder?, filter: ContentListFilter?): List<Content> = emptyList()
		override suspend fun getDetails(manga: Content): Content = manga
		override suspend fun getPages(chapter: ContentChapter, nextChapterUrl: String?): List<ContentPage> = emptyList()
		override suspend fun getPageUrl(page: ContentPage): String = ""
		override suspend fun getFilterOptions(): ContentListFilterOptions = ContentListFilterOptions()
		override suspend fun getChapterContent(chapter: ContentChapter, nextChapterUrl: String?): NovelChapterContent? = null
		override suspend fun getRelated(seed: Content): List<Content> = emptyList()
	}
}
