@file:OptIn(InternalParsersApi::class)

package org.skepsun.kototoro.parsers.site.fixture

import org.skepsun.kototoro.parsers.Broken
import org.skepsun.kototoro.parsers.ContentLoaderContext
import org.skepsun.kototoro.parsers.ContentSourceParser
import org.skepsun.kototoro.parsers.InternalParsersApi
import org.skepsun.kototoro.parsers.config.ConfigKey
import org.skepsun.kototoro.parsers.core.AbstractContentParser
import org.skepsun.kototoro.parsers.model.*

/**
 * Android's `@Broken` marker in a real plugin jar: the source is installed and usable, the app only hides it from the
 * source lists until the user turns on "show broken sources".
 */
@Broken("fixture: the site is offline")
@ContentSourceParser(name = "FIXTURE_BROKEN", title = "Fixture Broken", locale = "en", type = ContentType.MANGA)
internal class BrokenFixtureParser(context: ContentLoaderContext, source: ContentSource) :
    AbstractContentParser(context, source) {

    override val availableSortOrders: Set<SortOrder> = linkedSetOf(SortOrder.POPULARITY)
    override val filterCapabilities = ContentListFilterCapabilities()
    override val configKeyDomain = ConfigKey.Domain("broken.example")

    override suspend fun getList(offset: Int, order: SortOrder, filter: ContentListFilter): List<Content> = emptyList()

    override suspend fun getDetails(manga: Content): Content = manga

    override suspend fun getPages(chapter: ContentChapter): List<ContentPage> = emptyList()

    override suspend fun getFilterOptions(): ContentListFilterOptions = ContentListFilterOptions()
}
