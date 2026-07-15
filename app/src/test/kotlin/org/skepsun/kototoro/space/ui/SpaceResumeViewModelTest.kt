package org.skepsun.kototoro.space.ui

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.maps.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.mockk.every
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
import org.skepsun.kototoro.core.model.LocalMangaSource
import org.skepsun.kototoro.core.util.Event
import org.skepsun.kototoro.parsers.model.Content
import org.skepsun.kototoro.parsers.model.ContentSource
import org.skepsun.kototoro.parsers.model.ContentType
import org.skepsun.kototoro.parsers.model.RATING_UNKNOWN
import org.skepsun.kototoro.space.domain.BuiltInSpaces
import org.skepsun.kototoro.space.domain.SpaceId
import org.skepsun.kototoro.space.domain.SpaceRepository
import org.skepsun.kototoro.space.data.TestSpaceCatalogRepository

@OptIn(ExperimentalCoroutinesApi::class)
class SpaceResumeViewModelTest {

	private val dispatcher = UnconfinedTestDispatcher()

	@BeforeEach
	fun setUp() = Dispatchers.setMain(dispatcher)

	@AfterEach
	fun tearDown() = Dispatchers.resetMain()

	@Test
	fun `state maps recent content for every built in space`() {
		val manga = content(1L, "Manga", ContentType.MANGA)
		val novel = content(2L, "Novel", ContentType.NOVEL)
		val anime = content(3L, "Anime", ContentType.VIDEO)

		val state = buildSpaceResumeUiState(
			recent = mapOf(
				BuiltInSpaces.Manga to manga,
				BuiltInSpaces.Novel to novel,
				BuiltInSpaces.Anime to anime,
			),
			isOnline = true,
			resumeEnabled = true,
		)

		state.items.mapValues { it.value.title } shouldContainExactly mapOf(
			BuiltInSpaces.Manga to "Manga",
			BuiltInSpaces.Novel to "Novel",
			BuiltInSpaces.Anime to "Anime",
		)
		state.items.values.map(SpaceResumeItem::canResume) shouldContainExactly listOf(true, true, true)
	}

	@Test
	fun `offline state allows local content and omits missing history`() {
		val remote = content(1L, "Remote", ContentType.MANGA)
		val local = content(2L, "Local", source = LocalMangaSource)

		val state = buildSpaceResumeUiState(
			recent = mapOf(
				BuiltInSpaces.Manga to remote,
				BuiltInSpaces.Novel to null,
				BuiltInSpaces.Anime to local,
			),
			isOnline = false,
			resumeEnabled = true,
		)

		state.items.keys shouldBe setOf(BuiltInSpaces.Manga, BuiltInSpaces.Anime)
		state.items.getValue(BuiltInSpaces.Manga).canResume shouldBe false
		state.items.getValue(BuiltInSpaces.Anime).canResume shouldBe true
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
		val content = content(3L, "Anime", ContentType.MANGA)
		val state = SpaceResumeUiState(
			items = mapOf(
				BuiltInSpaces.Anime to SpaceResumeItem(
					spaceId = BuiltInSpaces.Anime,
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
		val viewModel = SpaceResumeViewModel(source, repository, TestSpaceCatalogRepository())

		withContext(Dispatchers.Default) {
			withTimeout(2_000) { viewModel.uiState.first { it == state } }
		}
		val opened = async(Dispatchers.Default) {
			withTimeout(2_000) { viewModel.onOpenReader.filterNotNull().first().request() }
		}
		viewModel.resume(BuiltInSpaces.Anime)

		opened.await() shouldBe SpaceResumeRequest(content, ContentType.VIDEO)
		repository.activations shouldContainExactly listOf(BuiltInSpaces.Anime)
	}

	@Test
	fun `resume waits for history refresh after immersive switch`() = runTest {
		val content = content(4L, "Novel", ContentType.NOVEL)
		val states = MutableStateFlow(SpaceResumeUiState())
		val source = mockk<SpaceResumeStateSource> {
			every { observe() } returns states
		}
		val repository = RecordingSpaceRepository()
		val viewModel = SpaceResumeViewModel(source, repository, TestSpaceCatalogRepository())
		val opened = async(Dispatchers.Default) {
			withTimeout(2_000) { viewModel.onOpenReader.filterNotNull().first().request() }
		}

		viewModel.resume(BuiltInSpaces.Novel)
		states.value = SpaceResumeUiState(
			items = mapOf(
				BuiltInSpaces.Novel to SpaceResumeItem(
					spaceId = BuiltInSpaces.Novel,
					title = content.title,
					content = content,
					canResume = true,
				),
			),
		)

		opened.await() shouldBe SpaceResumeRequest(content, ContentType.NOVEL)
		repository.activations shouldContainExactly listOf(BuiltInSpaces.Novel)
	}

	private suspend fun Event<SpaceResumeRequest>.request(): SpaceResumeRequest {
		var request: SpaceResumeRequest? = null
		consume(FlowCollector { request = it })
		return checkNotNull(request)
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
