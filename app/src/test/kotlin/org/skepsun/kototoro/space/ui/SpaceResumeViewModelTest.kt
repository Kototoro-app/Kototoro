package org.skepsun.kototoro.space.ui

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.maps.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.core.model.ContentHistory
import org.skepsun.kototoro.core.model.LocalMangaSource
import org.skepsun.kototoro.core.util.Event
import org.skepsun.kototoro.history.data.HistoryRepository
import org.skepsun.kototoro.parsers.model.Content
import org.skepsun.kototoro.parsers.model.ContentSource
import org.skepsun.kototoro.parsers.model.ContentType
import org.skepsun.kototoro.parsers.model.RATING_UNKNOWN
import org.skepsun.kototoro.space.domain.BuiltInSpaces
import org.skepsun.kototoro.space.domain.SpaceId
import org.skepsun.kototoro.space.domain.SpaceRepository
import org.skepsun.kototoro.space.data.SecondTestSpace
import org.skepsun.kototoro.space.data.TestSpaceCatalogRepository
import java.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class SpaceResumeViewModelTest {

	private val emptyTestSpaceId = SpaceId("custom:no-history")

	private val dispatcher = UnconfinedTestDispatcher()

	@BeforeEach
	fun setUp() = Dispatchers.setMain(dispatcher)

	@AfterEach
	fun tearDown() = Dispatchers.resetMain()

	@Test
	fun `state maps recent content for every space`() {
		val manga = content(1L, "Manga", ContentType.MANGA)
		val second = content(2L, "Second", ContentType.MANGA)

		val state = buildSpaceResumeUiState(
			recent = mapOf(
				BuiltInSpaces.Manga to manga,
				SecondTestSpace.id to second,
			),
			isOnline = true,
			resumeEnabled = true,
		)

		state.items.mapValues { it.value.title } shouldContainExactly mapOf(
			BuiltInSpaces.Manga to "Manga",
			SecondTestSpace.id to "Second",
		)
		state.items.values.map(SpaceResumeItem::canResume) shouldContainExactly listOf(true, true)
	}

	@Test
	fun `offline state allows local content and omits missing history`() {
		val remote = content(1L, "Remote", ContentType.MANGA)
		val local = content(2L, "Local", source = LocalMangaSource)

		val state = buildSpaceResumeUiState(
			recent = mapOf(
				BuiltInSpaces.Manga to remote,
				emptyTestSpaceId to null,
				SecondTestSpace.id to local,
			),
			isOnline = false,
			resumeEnabled = true,
		)

		state.items.keys shouldBe setOf(BuiltInSpaces.Manga, SecondTestSpace.id)
		state.items.getValue(BuiltInSpaces.Manga).canResume shouldBe false
		state.items.getValue(SecondTestSpace.id).canResume shouldBe true
	}

	@Test
	fun `disabled resume gate keeps recent context without enabling actions`() {
		val state = buildSpaceResumeUiState(
			recent = mapOf(BuiltInSpaces.Manga to content(1L, "Manga", ContentType.MANGA)),
			isOnline = true,
			resumeEnabled = false,
		)

		state.items.getValue(BuiltInSpaces.Manga).canResume shouldBe false
	}

	@Test
	fun `resume activates target space before emitting content`() = runTest {
		val content = content(3L, "Second", ContentType.MANGA)
		val state = SpaceResumeUiState(
			items = mapOf(
				SecondTestSpace.id to SpaceResumeItem(
					spaceId = SecondTestSpace.id,
					title = content.title,
					content = content,
					canResume = true,
				),
			),
		)
		val source = mockk<SpaceResumeStateSource> {
			every { observe() } returns flowOf(state)
		}
		val repository = RecordingSpaceRepository()
		val history = ContentHistory(
			createdAt = Instant.EPOCH,
			updatedAt = Instant.EPOCH,
			chapterId = 42L,
			page = 3,
			scroll = 4,
			percent = 0.5f,
			chaptersCount = 10,
		)
		val viewModel = SpaceResumeViewModel(
			source,
			repository,
			TestSpaceCatalogRepository(BuiltInSpaces.contexts + SecondTestSpace),
			historyRepository(history),
		)

		withContext(Dispatchers.Default) {
			withTimeout(2_000) { viewModel.uiState.first { it == state } }
		}
		val opened = async(Dispatchers.Default) {
			withTimeout(2_000) { viewModel.onOpenReader.filterNotNull().first().request() }
		}
		viewModel.resume(SecondTestSpace.id)

		opened.await() shouldBe SpaceResumeRequest(
			content = content,
			contentType = ContentType.MANGA,
			state = org.skepsun.kototoro.reader.ui.ReaderState(42L, 3, 4),
		)
		repository.activations shouldContainExactly listOf(SecondTestSpace.id)
	}

	@Test
	fun `resume waits for history refresh after immersive switch`() = runTest {
		val content = content(4L, "Second", ContentType.MANGA)
		val states = MutableStateFlow(SpaceResumeUiState())
		val source = mockk<SpaceResumeStateSource> {
			every { observe() } returns states
		}
		val repository = RecordingSpaceRepository()
		val viewModel = SpaceResumeViewModel(
			source,
			repository,
			TestSpaceCatalogRepository(BuiltInSpaces.contexts + SecondTestSpace),
			emptyHistoryRepository(),
		)
		val opened = async(Dispatchers.Default) {
			withTimeout(2_000) { viewModel.onOpenReader.filterNotNull().first().request() }
		}

		viewModel.resume(SecondTestSpace.id)
		states.value = SpaceResumeUiState(
			items = mapOf(
				SecondTestSpace.id to SpaceResumeItem(
					spaceId = SecondTestSpace.id,
					title = content.title,
					content = content,
					canResume = true,
				),
			),
		)

		opened.await() shouldBe SpaceResumeRequest(content, ContentType.MANGA, state = null)
		repository.activations shouldContainExactly listOf(SecondTestSpace.id)
	}

	private suspend fun Event<SpaceResumeRequest>.request(): SpaceResumeRequest {
		var request: SpaceResumeRequest? = null
		consume(FlowCollector { request = it })
		return checkNotNull(request)
	}

	private fun emptyHistoryRepository() = historyRepository(null)

	private fun historyRepository(history: ContentHistory?) = mockk<HistoryRepository> {
		coEvery { getOne(any()) } returns history
	}

	private fun content(id: Long, title: String, type: ContentType): Content {
		return content(id, title, TestContentSource(type))
	}

	private fun content(id: Long, title: String, source: ContentSource): Content {
		return Content(
			id = id,
			title = title,
			altTitles = emptySet(),
			url = "/$id",
			publicUrl = "https://example.org/$id",
			rating = RATING_UNKNOWN,
			contentRating = null,
			coverUrl = null,
			tags = emptySet(),
			state = null,
			authors = emptySet(),
			source = source,
		)
	}

	private data class TestContentSource(
		override val contentType: ContentType,
	) : ContentSource {
		override val name = "test-$contentType"
		override val locale = ""
	}

	private class RecordingSpaceRepository : SpaceRepository {
		override val activeSpace = kotlinx.coroutines.flow.MutableStateFlow(BuiltInSpaces.Manga)
		val activations = mutableListOf<SpaceId>()

		override suspend fun activate(spaceId: SpaceId) {
			activations += spaceId
			activeSpace.value = spaceId
		}
	}
}
