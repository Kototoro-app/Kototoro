package org.skepsun.kototoro.core.parser.external

import android.content.ContentResolver
import android.database.Cursor
import androidx.annotation.WorkerThread
import androidx.collection.ArraySet
import androidx.core.net.toUri
import org.jetbrains.annotations.Blocking
import org.skepsun.kototoro.core.exceptions.IncompatiblePluginException
import org.skepsun.kototoro.parsers.model.ContentRating
import org.skepsun.kototoro.parsers.model.ContentType
import org.skepsun.kototoro.parsers.model.Demographic
import org.skepsun.kototoro.parsers.model.Content
import org.skepsun.kototoro.parsers.model.ContentChapter
import org.skepsun.kototoro.parsers.model.ContentListFilter
import org.skepsun.kototoro.parsers.model.ContentListFilterCapabilities
import org.skepsun.kototoro.parsers.model.ContentListFilterOptions
import org.skepsun.kototoro.parsers.model.ContentPage
import org.skepsun.kototoro.parsers.model.ContentState
import org.skepsun.kototoro.parsers.model.ContentTag
import org.skepsun.kototoro.parsers.model.SortOrder
import org.skepsun.kototoro.parsers.util.find
import org.skepsun.kototoro.parsers.util.ifNullOrEmpty
import org.skepsun.kototoro.parsers.util.mapNotNullToSet
import org.skepsun.kototoro.parsers.util.nullIfEmpty
import org.skepsun.kototoro.parsers.util.splitTwoParts
import java.util.EnumSet
import java.util.Locale

class ExternalPluginContentSource(
    private val contentResolver: ContentResolver,
    private val source: ExternalContentSource,
) {

    @Blocking
    @WorkerThread
    fun getListFilterOptions() = ContentListFilterOptions(
        availableTags = fetchTags(),
        availableStates = fetchEnumSet(ContentState::class.java, "filter/states"),
        availableContentRating = fetchEnumSet(ContentRating::class.java, "filter/content_ratings"),
        availableContentTypes = fetchEnumSet(ContentType::class.java, "filter/content_types"),
        availableDemographics = fetchEnumSet(Demographic::class.java, "filter/demographics"),
        availableLocales = fetchLocales(),
    )

    @Blocking
    @WorkerThread
    fun getList(offset: Int, order: SortOrder, filter: ContentListFilter): List<Content> {
        val uri = "content://${source.authority}/manga".toUri().buildUpon()
        uri.appendQueryParameter("offset", offset.toString())
        filter.tags.forEach { uri.appendQueryParameter("tags_include", "${it.key}=${it.title}") }
        filter.tagsExclude.forEach { uri.appendQueryParameter("tags_exclude", "${it.key}=${it.title}") }
        filter.states.forEach { uri.appendQueryParameter("state", it.name) }
        filter.locale?.let { uri.appendQueryParameter("locale", it.language) }
        filter.contentRating.forEach { uri.appendQueryParameter("content_rating", it.name) }
        if (!filter.author.isNullOrEmpty()) {
            uri.appendQueryParameter("author", filter.author)
        }
        if (!filter.query.isNullOrEmpty()) {
            uri.appendQueryParameter("query", filter.query)
        }
        return contentResolver.query(uri.build(), null, null, null, order.name)
            .safe()
            .use { cursor ->
                val result = ArrayList<Content>(cursor.count)
                if (cursor.moveToFirst()) {
                    do {
                        result += cursor.getContent()
                    } while (cursor.moveToNext())
                }
                result
            }
    }

    @Blocking
    @WorkerThread
    fun getDetails(manga: Content): Content {
        val chapters = queryChapters(manga.url)
        val details = queryDetails(manga.url)
        return Content(
            id = manga.id,
            title = details.title.ifBlank { manga.title },
            altTitles = details.altTitles.ifEmpty { manga.altTitles },
            url = details.url.ifEmpty { manga.url },
            publicUrl = details.publicUrl.ifEmpty { manga.publicUrl },
            rating = maxOf(details.rating, manga.rating),
            contentRating = details.contentRating,
            coverUrl = details.coverUrl.ifNullOrEmpty { manga.coverUrl },
            tags = details.tags + manga.tags,
            state = details.state ?: manga.state,
            authors = details.authors.ifEmpty { manga.authors },
            largeCoverUrl = details.largeCoverUrl.ifNullOrEmpty { manga.largeCoverUrl },
            description = details.description.ifNullOrEmpty { manga.description },
            chapters = chapters,
            source = source,
        )
    }

    @Blocking
    @WorkerThread
    fun getPages(chapter: ContentChapter): List<ContentPage> {
        val uri = "content://${source.authority}/chapters".toUri()
            .buildUpon()
            .appendPath(chapter.url)
            .build()
        return contentResolver.query(uri, null, null, null, null)
            .safe()
            .use { cursor ->
                val result = ArrayList<ContentPage>(cursor.count)
                if (cursor.moveToFirst()) {
                    do {
                        result += ContentPage(
                            id = cursor.getLong(COLUMN_ID),
                            url = cursor.getString(COLUMN_URL),
                            preview = cursor.getStringOrNull(COLUMN_PREVIEW),
                            source = source,
                        )
                    } while (cursor.moveToNext())
                }
                result
            }
    }

    @Blocking
    @WorkerThread
    private fun fetchTags(): Set<ContentTag> {
        val uri = "content://${source.authority}/filter/tags".toUri()
        return contentResolver.query(uri, null, null, null, null)
            .safe()
            .use { cursor ->
                val result = ArraySet<ContentTag>(cursor.count)
                if (cursor.moveToFirst()) {
                    do {
                        result += ContentTag(
                            key = cursor.getString(COLUMN_KEY),
                            title = cursor.getString(COLUMN_TITLE),
                            source = source,
                        )
                    } while (cursor.moveToNext())
                }
                result
            }
    }

    @Blocking
    @WorkerThread
    fun getPageUrl(url: String): String {
        val uri = "content://${source.authority}/manga/pages/0".toUri().buildUpon()
            .appendQueryParameter("url", url)
            .build()
        return contentResolver.query(uri, null, null, null, null)
            .safe()
            .use { cursor ->
                if (cursor.moveToFirst()) {
                    cursor.getString(COLUMN_VALUE)
                } else {
                    url
                }
            }
    }

    @Blocking
    @WorkerThread
    private fun fetchLocales(): Set<Locale> {
        val uri = "content://${source.authority}/filter/locales".toUri()
        return contentResolver.query(uri, null, null, null, null)
            .safe()
            .use { cursor ->
                val result = ArraySet<Locale>(cursor.count)
                if (cursor.moveToFirst()) {
                    do {
                        result += Locale(cursor.getString(COLUMN_NAME))
                    } while (cursor.moveToNext())
                }
                result
            }
    }

    fun getCapabilities(): ContentSourceCapabilities? {
        val uri = "content://${source.authority}/capabilities".toUri()
        return contentResolver.query(uri, null, null, null, null)
            .safe()
            .use { cursor ->
                if (cursor.moveToFirst()) {
                    ContentSourceCapabilities(
                        availableSortOrders = cursor.getStringOrNull(COLUMN_SORT_ORDERS)
                            ?.split(',')
                            ?.mapNotNullTo(EnumSet.noneOf(SortOrder::class.java)) {
                                SortOrder.entries.find(it)
                            }.orEmpty(),
                        listFilterCapabilities = ContentListFilterCapabilities(
                            isMultipleTagsSupported = cursor.getBooleanOrDefault(COLUMN_MULTIPLE_TAGS, false),
                            isTagsExclusionSupported = cursor.getBooleanOrDefault(COLUMN_TAGS_EXCLUSION, false),
                            isSearchSupported = cursor.getBooleanOrDefault(COLUMN_SEARCH, false),
                            isSearchWithFiltersSupported = cursor.getBooleanOrDefault(
                                COLUMN_SEARCH_WITH_FILTERS,
                                false,
                            ),
                            isYearSupported = cursor.getBooleanOrDefault(COLUMN_YEAR, false),
                            isYearRangeSupported = cursor.getBooleanOrDefault(COLUMN_YEAR_RANGE, false),
                            isOriginalLocaleSupported = cursor.getBooleanOrDefault(COLUMN_ORIGINAL_LOCALE, false),
                            isAuthorSearchSupported = cursor.getBooleanOrDefault(COLUMN_AUTHOR, false),
                        ),
                    )
                } else {
                    null
                }
            }
    }

    private fun queryDetails(url: String): Content {
        val uri = "content://${source.authority}/manga".toUri()
            .buildUpon()
            .appendPath(url)
            .build()
        return contentResolver.query(uri, null, null, null, null)
            .safe()
            .use { cursor ->
                cursor.moveToFirst()
                cursor.getContent()
            }
    }

    private fun queryChapters(url: String): List<ContentChapter> {
        val uri = "content://${source.authority}/manga/chapters".toUri()
            .buildUpon()
            .appendPath(url)
            .build()
        return contentResolver.query(uri, null, null, null, null)
            .safe()
            .use { cursor ->
                val result = ArrayList<ContentChapter>(cursor.count)
                if (cursor.moveToFirst()) {
                    do {
                        result += ContentChapter(
                            id = cursor.getLong(COLUMN_ID),
                            title = cursor.getStringOrNull(COLUMN_NAME),
                            number = cursor.getFloatOrDefault(COLUMN_NUMBER, 0f),
                            volume = cursor.getIntOrDefault(COLUMN_VOLUME, 0),
                            url = cursor.getString(COLUMN_URL),
                            scanlator = cursor.getStringOrNull(COLUMN_SCANLATOR),
                            uploadDate = cursor.getLongOrDefault(COLUMN_UPLOAD_DATE, 0L),
                            branch = cursor.getStringOrNull(COLUMN_BRANCH),
                            source = source,
                        )
                    } while (cursor.moveToNext())
                }
                result
            }
    }

    private fun ExternalPluginCursor.getContent() = Content(
        id = getLong(COLUMN_ID),
        title = getString(COLUMN_TITLE),
        altTitles = setOfNotNull(getStringOrNull(COLUMN_ALT_TITLE)),
        url = getString(COLUMN_URL),
        publicUrl = getString(COLUMN_PUBLIC_URL),
        rating = getFloat(COLUMN_RATING),
        contentRating = if (getBooleanOrDefault(COLUMN_IS_NSFW, false)) {
            ContentRating.ADULT
        } else {
            null
        },
        coverUrl = getStringOrNull(COLUMN_COVER_URL),
        tags = getStringOrNull(COLUMN_TAGS)?.split(':')?.mapNotNullToSet {
            val parts = it.splitTwoParts('=') ?: return@mapNotNullToSet null
            ContentTag(key = parts.first, title = parts.second, source = source)
        }.orEmpty(),
        state = getStringOrNull(COLUMN_STATE)?.let { ContentState.entries.find(it) },
        authors = getStringOrNull(COLUMN_AUTHOR)?.split(',')?.mapNotNullToSet {
            it.trim().nullIfEmpty()
        }.orEmpty(),
        largeCoverUrl = getStringOrNull(COLUMN_LARGE_COVER_URL),
        description = getStringOrNull(COLUMN_DESCRIPTION),
        chapters = emptyList(),
        source = source,
    )

    private fun <E : Enum<E>> fetchEnumSet(cls: Class<E>, path: String): EnumSet<E> {
        val uri = "content://${source.authority}/$path".toUri()
        return contentResolver.query(uri, null, null, null, null)
            .safe()
            .use { cursor ->
                val result = EnumSet.noneOf(cls)
                val enumConstants = cls.enumConstants ?: return@use result
                if (cursor.moveToFirst()) {
                    do {
                        val name = cursor.getString(COLUMN_NAME)
                        val enumValue = enumConstants.find { it.name == name }
                        if (enumValue != null) {
                            result.add(enumValue)
                        }
                    } while (cursor.moveToNext())
                }
                result
            }
    }

    private fun Cursor?.safe() = ExternalPluginCursor(
        source = source,
        cursor = this ?: throw IncompatiblePluginException(source.name, null),
    )

    class ContentSourceCapabilities(
        val availableSortOrders: Set<SortOrder>,
        val listFilterCapabilities: ContentListFilterCapabilities,
    )

    private companion object {

        const val COLUMN_SORT_ORDERS = "sort_orders"
        const val COLUMN_MULTIPLE_TAGS = "multiple_tags"
        const val COLUMN_TAGS_EXCLUSION = "tags_exclusion"
        const val COLUMN_SEARCH = "search"
        const val COLUMN_SEARCH_WITH_FILTERS = "search_with_filters"
        const val COLUMN_YEAR = "year"
        const val COLUMN_YEAR_RANGE = "year_range"
        const val COLUMN_ORIGINAL_LOCALE = "original_locale"
        const val COLUMN_ID = "id"
        const val COLUMN_NAME = "name"
        const val COLUMN_NUMBER = "number"
        const val COLUMN_VOLUME = "volume"
        const val COLUMN_URL = "url"
        const val COLUMN_SCANLATOR = "scanlator"
        const val COLUMN_UPLOAD_DATE = "upload_date"
        const val COLUMN_BRANCH = "branch"
        const val COLUMN_TITLE = "title"
        const val COLUMN_ALT_TITLE = "alt_title"
        const val COLUMN_PUBLIC_URL = "public_url"
        const val COLUMN_RATING = "rating"
        const val COLUMN_IS_NSFW = "is_nsfw"
        const val COLUMN_COVER_URL = "cover_url"
        const val COLUMN_TAGS = "tags"
        const val COLUMN_STATE = "state"
        const val COLUMN_AUTHOR = "author"
        const val COLUMN_LARGE_COVER_URL = "large_cover_url"
        const val COLUMN_DESCRIPTION = "description"
        const val COLUMN_PREVIEW = "preview"
        const val COLUMN_KEY = "key"
        const val COLUMN_VALUE = "value"
    }
}
