package org.skepsun.kototoro.local.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.skepsun.kototoro.core.db.MangaDatabase
import org.skepsun.kototoro.core.db.entity.MangaEntity
import org.skepsun.kototoro.core.model.LocalMangaSource
import org.skepsun.kototoro.core.model.LocalNovelSource
import org.skepsun.kototoro.core.model.LocalVideoSource
import org.skepsun.kototoro.local.data.index.LocalContentIndexEntity
import org.skepsun.kototoro.parsers.model.ContentListFilter
import org.skepsun.kototoro.parsers.model.ContentRating
import org.skepsun.kototoro.parsers.model.ContentType
import org.skepsun.kototoro.parsers.model.SortOrder
import javax.inject.Inject

/**
 * 验证 §6.5 本地页改造：
 * - 本地列表从数据库（local_index 为主路径）读取，不依赖文件系统扫描；
 * - 浏览走 OFFSET 分页（每页 [LOCAL_PAGE_SIZE] 条），搜索带 query 时返回完整结果集；
 * - getAll() 覆盖批量消费方（删除等），过滤与排序语义与旧实现一致。
 *
 * 通过预置 "_local_index/ver=4" 跳过 LocalContentIndex 的全量扫描修复，隔离数据库读取路径。
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class LocalLibraryPagingTest {

	@get:Rule
	val hiltRule = HiltAndroidRule(this)

	@Inject
	lateinit var repository: LocalMangaRepository

	@Inject
	lateinit var db: MangaDatabase

	private companion object {
		const val INDEX_PREF_NAME = "_local_index"
		const val INDEX_VERSION = 4
		const val PAGE_SIZE = 128
	}

	private val context: Context
		get() = ApplicationProvider.getApplicationContext()

	@Before
	fun setUp() {
		hiltRule.inject()
		// 让 LocalContentIndex.updateIfRequired() 判定为已是最新，跳过清库+扫描。
		context.getSharedPreferences(INDEX_PREF_NAME, Context.MODE_PRIVATE)
			.edit()
			.putInt("ver", INDEX_VERSION)
			.commit()
		db.openHelper.writableDatabase.execSQL("DELETE FROM local_index")
		db.openHelper.writableDatabase.execSQL("DELETE FROM manga")
	}

	@After
	fun tearDown() {
		db.openHelper.writableDatabase.execSQL("DELETE FROM local_index")
		db.openHelper.writableDatabase.execSQL("DELETE FROM manga")
	}

	private suspend fun seedManga(
		count: Int,
		prefix: String = "Manga",
		source: String = LocalMangaSource.name,
		contentType: ContentType = ContentType.MANGA,
		nsfw: Boolean = false,
		startId: Long = 1L,
	) {
		val dao = db.getMangaDao()
		(startId until startId + count).forEach { id ->
			val title = "$prefix " + id.toString().padStart(3, '0')
			dao.upsert(
				MangaEntity(
					id = id,
					title = title,
					altTitles = null,
					url = "file:///storage/emulated/0/Manga/" + title.replace(' ', '_') + ".cbz",
					publicUrl = "",
					rating = 0.5f,
					isNsfw = nsfw,
					contentRating = if (nsfw) ContentRating.ADULT.name else null,
					coverUrl = "",
					largeCoverUrl = null,
					state = null,
					authors = null,
					source = source,
					description = null,
					contentType = contentType.name,
				),
			)
			db.getLocalContentIndexDao().upsert(
				LocalContentIndexEntity(
					mangaId = id,
					path = "file:///storage/emulated/0/Manga/" + title.replace(' ', '_') + ".cbz",
				),
			)
		}
	}

	@Test
	fun browsePagesFromDatabaseWithStableOrderAndNoOverlap() = runTest {
		seedManga(300)

		val page1 = repository.getList(0, SortOrder.ALPHABETICAL, null)
		assertEquals(PAGE_SIZE, page1.size)
		assertEquals("Manga 001", page1.first().title)
		assertEquals("Manga 128", page1.last().title)

		val page2 = repository.getList(PAGE_SIZE, SortOrder.ALPHABETICAL, null)
		assertEquals(PAGE_SIZE, page2.size)
		assertEquals("Manga 129", page2.first().title)
		assertEquals("Manga 256", page2.last().title)

		val page3 = repository.getList(PAGE_SIZE * 2, SortOrder.ALPHABETICAL, null)
		assertEquals(300 - PAGE_SIZE * 2, page3.size)
		assertEquals("Manga 257", page3.first().title)

		val beyond = repository.getList(PAGE_SIZE * 3, SortOrder.ALPHABETICAL, null)
		assertTrue(beyond.isEmpty())

		val all = page1 + page2 + page3
		assertEquals(300, all.distinctBy { it.id }.size)
	}

	@Test
	fun searchQueryReturnsFullResultSetInsteadOfPaging() = runTest {
		seedManga(40, prefix = "Target")
		seedManga(20, prefix = "Other", startId = 1000L)

		val matches = repository.getList(0, SortOrder.ALPHABETICAL, ContentListFilter(query = "Target"))
		// 搜索/匹配返回完整结果集，不受分页截断。
		assertEquals(40, matches.size)
		assertTrue(matches.all { it.title.startsWith("Target") })
	}

	@Test
	fun filtersByTypeAndNsfwContentRating() = runTest {
		seedManga(10, source = LocalMangaSource.name, contentType = ContentType.MANGA)
		seedManga(5, source = LocalNovelSource.name, contentType = ContentType.NOVEL, startId = 100L)
		seedManga(3, source = LocalVideoSource.name, contentType = ContentType.VIDEO, startId = 200L)
		seedManga(2, source = LocalMangaSource.name, contentType = ContentType.MANGA, nsfw = true, startId = 300L)

		// 类型过滤按 source.contentType（LOCAL->MANGA, LOCAL_NOVEL->NOVEL, LOCAL_VIDEO->VIDEO），
		// 类型过滤不区分 NSFW，因此 MANGA 包含 10 条 SFW + 2 条成人漫画。
		val mangaOnly = repository.getList(0, null, ContentListFilter(types = setOf(ContentType.MANGA)))
		assertEquals(12, mangaOnly.size)

		val novelOnly = repository.getList(0, null, ContentListFilter(types = setOf(ContentType.NOVEL)))
		assertEquals(5, novelOnly.size)

		val videoOnly = repository.getList(0, null, ContentListFilter(types = setOf(ContentType.VIDEO)))
		assertEquals(3, videoOnly.size)

		// 内容分级过滤按 isNsfw()：SAFE 保留所有非成人（漫画+小说+视频），ADULT 只保留成人漫画。
		val safeOnly = repository.getList(0, null, ContentListFilter(contentRating = setOf(ContentRating.SAFE)))
		assertEquals(18, safeOnly.size)

		val adultOnly = repository.getList(0, null, ContentListFilter(contentRating = setOf(ContentRating.ADULT)))
		assertEquals(2, adultOnly.size)
	}

	@Test
	fun getAllReturnsTheFullDatabaseBackedList() = runTest {
		seedManga(33)

		val all = repository.getAll(SortOrder.ALPHABETICAL, null)
		assertEquals(33, all.size)

		// 浏览接口第 0 页只返回首页，完整读取交给 getAll。
		val firstPage = repository.getList(0, SortOrder.ALPHABETICAL, null)
		assertEquals(33, firstPage.size)
		assertEquals(33, all.distinctBy { it.id }.size)
	}
}
