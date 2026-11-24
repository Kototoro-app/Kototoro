package org.skepsun.kototoro.core.parser

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.parsers.model.ContentSource
import org.skepsun.kototoro.parsers.model.ContentType
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

class ContentSourceResolutionPipelineTest {

    private val contentSourceInfoResolver = mockk<ContentSourceInfoResolver>()
    private val jsonContentSourceResolver = mockk<JsonContentSourceResolver>()
    private val mihonContentSourceResolver = mockk<MihonContentSourceResolver>()
    private val aniyomiContentSourceResolver = mockk<AniyomiContentSourceResolver>()
    private val ireaderContentSourceResolver = mockk<IReaderContentSourceResolver>()
    private val tsundokuContentSourceResolver = mockk<TsundokuContentSourceResolver>()

    private val externalEpoch = AtomicLong(0L)
    private val mihonVersion = AtomicLong(0L)
    private val jsonVersion = AtomicLong(0L)

    @BeforeEach
    fun setUp() {
        listOf(
            contentSourceInfoResolver,
            jsonContentSourceResolver,
            mihonContentSourceResolver,
            aniyomiContentSourceResolver,
            ireaderContentSourceResolver,
            tsundokuContentSourceResolver,
        ).forEach { resolver ->
            every { resolver.supports(any()) } returns false
            every { resolver.resolve(any()) } returns null
            every { resolver.cacheVersion() } returns 0L
        }
        every { mihonContentSourceResolver.cacheVersion() } answers { mihonVersion.get() }
        every { jsonContentSourceResolver.cacheVersion() } answers { jsonVersion.get() }
    }

    @Test
    fun `resolveWithTrace caches resolved source and trace across repeated calls`() {
        val shell = TestSource("MIHON_42", ContentType.MANGA, "")
        val resolved = ResolvedTestSource("MIHON_42", ContentType.MANGA, "en")
        every { mihonContentSourceResolver.supports(shell) } returns true
        every { mihonContentSourceResolver.resolve(shell) } returns resolved
        every { mihonContentSourceResolver.supports(resolved) } returns false

        val pipeline = createPipeline()

        val first = pipeline.resolveWithTrace(shell)
        val second = pipeline.resolveWithTrace(shell)

        assertSame(resolved, first.resolvedSource)
        assertSame(resolved, second.resolvedSource)
        assertEquals(first.steps, second.steps)
        verify(exactly = 1) { mihonContentSourceResolver.resolve(shell) }
    }

    @Test
    fun `unchanged cache hit returns caller instance without retaining stale objects`() {
        val firstInput = TestSource("MANGADEX", ContentType.MANGA, "en")
        val secondInput = TestSource("MANGADEX", ContentType.MANGA, "en")
        val pipeline = createPipeline()

        val first = pipeline.resolveWithTrace(firstInput)
        val second = pipeline.resolveWithTrace(secondInput)

        assertSame(firstInput, first.resolvedSource)
        assertSame(secondInput, second.resolvedSource)
        verify(exactly = 1) { mihonContentSourceResolver.supports(any()) }
    }

    @Test
    fun `resolveByName caches sourceFactory and pipeline lookups`() {
        val factoryCalls = AtomicInteger(0)
        val resolved = ResolvedTestSource("MIHON_7", ContentType.MANGA, "ja")
        every { mihonContentSourceResolver.supports(match { it is TestSource && it.name == "MIHON_7" }) } returns true
        every { mihonContentSourceResolver.resolve(match { it is TestSource && it.name == "MIHON_7" }) } returns resolved

        val pipeline = createPipeline(
            sourceFactory = { name ->
                factoryCalls.incrementAndGet()
                TestSource(name, ContentType.MANGA, "")
            },
        )

        val first = pipeline.resolveByName("MIHON_7")
        val second = pipeline.resolveByName("MIHON_7")
        val third = pipeline.resolveByName("MIHON_7")

        assertSame(resolved, first)
        assertSame(resolved, second)
        assertSame(resolved, third)
        assertEquals(1, factoryCalls.get())
        verify(exactly = 1) { mihonContentSourceResolver.resolve(any()) }
    }

    @Test
    fun `resolver version change invalidates cached resolution`() {
        val shell = TestSource("MIHON_99", ContentType.MANGA, "")
        val resolvedV1 = ResolvedTestSource("MIHON_99", ContentType.MANGA, "en")
        val resolvedV2 = ResolvedTestSource("MIHON_99", ContentType.MANGA, "zh")
        every { mihonContentSourceResolver.supports(shell) } returns true
        every { mihonContentSourceResolver.resolve(shell) } returns resolvedV1

        val pipeline = createPipeline()

        assertSame(resolvedV1, pipeline.resolve(shell))
        assertSame(resolvedV1, pipeline.resolve(shell))
        verify(exactly = 1) { mihonContentSourceResolver.resolve(shell) }

        every { mihonContentSourceResolver.resolve(shell) } returns resolvedV2
        mihonVersion.incrementAndGet()

        assertSame(resolvedV2, pipeline.resolve(shell))
        verify(exactly = 2) { mihonContentSourceResolver.resolve(shell) }
    }

    @Test
    fun `external epoch change invalidates resolveByName cache`() {
        val v1 = ResolvedTestSource("MANGADEX", ContentType.MANGA, "")
        val v2 = ResolvedTestSource("MANGADEX", ContentType.MANGA, "en")
        var current: ContentSource = v1

        val pipeline = createPipeline(sourceFactory = { current })

        assertSame(v1, pipeline.resolveByName("MANGADEX"))
        assertSame(v1, pipeline.resolveByName("MANGADEX"))

        current = v2
        externalEpoch.incrementAndGet()

        assertSame(v2, pipeline.resolveByName("MANGADEX"))
    }

    @Test
    fun `manual invalidate drops cached entries`() {
        val shell = TestSource("JSON_JS_DEMO", ContentType.MANGA, "")
        val resolvedV1 = ResolvedTestSource("JSON_JS_DEMO", ContentType.MANGA, "en")
        val resolvedV2 = ResolvedTestSource("JSON_JS_DEMO", ContentType.NOVEL, "zh")
        every { jsonContentSourceResolver.supports(shell) } returns true
        every { jsonContentSourceResolver.resolve(shell) } returns resolvedV1

        val pipeline = createPipeline()
        assertSame(resolvedV1, pipeline.resolve(shell))

        every { jsonContentSourceResolver.resolve(shell) } returns resolvedV2
        pipeline.invalidate()

        assertSame(resolvedV2, pipeline.resolve(shell))
        verify(exactly = 2) { jsonContentSourceResolver.resolve(shell) }
    }

    @Test
    fun `cache respects maxCacheEntries bound`() {
        val factoryCalls = AtomicInteger(0)
        val pipeline = createPipeline(
            sourceFactory = { name ->
                factoryCalls.incrementAndGet()
                TestSource(name, ContentType.MANGA, "en")
            },
            maxCacheEntries = 2,
        )

        pipeline.resolveByName("S1")
        pipeline.resolveByName("S2")
        pipeline.resolveByName("S3")
        pipeline.resolveByName("S1")
        pipeline.resolveByName("S2")
        pipeline.resolveByName("S3")

        // S1 and S2 stay cached (1 call each); S3 is beyond the cap so it recomputes (2 calls).
        assertEquals(4, factoryCalls.get())
    }

    @Test
    fun `concurrent resolution and invalidation stay thread-safe and observe latest version`() {
        val v1 = ResolvedTestSource("MIHON_CONCURRENT", ContentType.MANGA, "en")
        val v2 = ResolvedTestSource("MIHON_CONCURRENT", ContentType.MANGA, "zh")
        every { mihonContentSourceResolver.supports(match { it is TestSource }) } returns true
        every { mihonContentSourceResolver.resolve(match { it is TestSource }) } answers {
            if (mihonVersion.get() == 0L) v1 else v2
        }

        val pipeline = createPipeline(
            sourceFactory = { name -> TestSource(name, ContentType.MANGA, "") },
        )
        val executor = Executors.newFixedThreadPool(8)
        try {
            val startLatch = CountDownLatch(1)
            val tasks = (0 until 16).map { index ->
                Callable {
                    startLatch.await(5, TimeUnit.SECONDS)
                    repeat(50) { step ->
                        if (index == 0 && step == 25) {
                            mihonVersion.set(1L)
                        }
                        val resolved = pipeline.resolveByName("MIHON_CONCURRENT")
                        assertEquals("MIHON_CONCURRENT", resolved.name)
                    }
                }
            }
            val futures = tasks.map { executor.submit(it) }
            startLatch.countDown()
            futures.forEach { it.get(10, TimeUnit.SECONDS) }
            assertSame(v2, pipeline.resolveByName("MIHON_CONCURRENT"))
        } finally {
            executor.shutdownNow()
        }
    }

    private fun createPipeline(
        sourceFactory: (String) -> ContentSource = { name -> TestSource(name, ContentType.MANGA, "") },
        maxCacheEntries: Int = 1024,
    ): ContentSourceResolutionPipeline = ContentSourceResolutionPipeline(
        contentSourceInfoResolver = contentSourceInfoResolver,
        jsonContentSourceResolver = jsonContentSourceResolver,
        mihonContentSourceResolver = mihonContentSourceResolver,
        aniyomiContentSourceResolver = aniyomiContentSourceResolver,
        ireaderContentSourceResolver = ireaderContentSourceResolver,
        tsundokuContentSourceResolver = tsundokuContentSourceResolver,
        sourceFactory = sourceFactory,
        externalEpochProvider = { externalEpoch.get() },
        maxCacheEntries = maxCacheEntries,
    )

    private data class TestSource(
        override val name: String,
        override val contentType: ContentType,
        override val locale: String,
    ) : ContentSource

    private data class ResolvedTestSource(
        override val name: String,
        override val contentType: ContentType,
        override val locale: String,
    ) : ContentSource
}
