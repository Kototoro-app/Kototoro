package org.skepsun.kototoro.main.ui.navigation3

import org.skepsun.kototoro.core.nav.PendingContentListNavigation
import org.skepsun.kototoro.core.nav.PendingDetailsNavigation
import org.skepsun.kototoro.details.ui.model.DetailsOrigin
import org.skepsun.kototoro.main.ui.MainActivity
import org.skepsun.kototoro.parsers.model.Content
import org.skepsun.kototoro.parsers.model.ContentListFilter
import org.skepsun.kototoro.parsers.model.ContentSource
import org.skepsun.kototoro.parsers.model.SortOrder
import org.skepsun.kototoro.search.ui.compose.SearchNavigationRequest

/**
 * [MainNavigator] implementation that operates directly on the Navigation 3
 * [MainNavState] back stacks.
 *
 * Content list / details / search are pushed onto the selected top-level's v3
 * stack, where [MainTopLevelNavDisplay] renders them as immersive destinations.
 * There is no Navigation 2 [androidx.navigation.NavHostController] involved:
 * top-level switching only mutates the selected-top-level state, and [pop]
 * just removes the current stack's top entry.
 */
class MainStateNavigator(
    private val mainActivity: MainActivity?,
    private val mainNavState: MainNavState,
    private val onDetailsTransitionRequested: () -> Unit = {},
) : MainNavigator {

    override fun openTopLevel(key: TopLevelNavKey) {
        mainNavState.navigateTopLevel(key)
    }

    override fun openContentList(
        source: ContentSource,
        filter: ContentListFilter?,
        sortOrder: SortOrder?,
    ) {
        onDetailsTransitionRequested()
        mainNavState.push(ContentListNavKey(sourceName = source.name))
        PendingContentListNavigation.set(filter = filter, sortOrder = sortOrder)
    }

    override fun openDetails(
        content: Content,
        sharedElementKey: String?,
    ) {
        onDetailsTransitionRequested()
        mainActivity?.resolveDetailsOriginForContent(content) { origin ->
            mainNavState.push(origin.toDetailsNavKey())
            PendingDetailsNavigation.set(origin, sharedElementKey)
        } ?: run {
            mainNavState.push(DetailsNavKey(requestedProjectionId = content.id))
            PendingDetailsNavigation.set(content, sharedElementKey)
        }
    }

    override fun openDetails(
        origin: DetailsOrigin,
        sharedElementKey: String?,
    ) {
        onDetailsTransitionRequested()
        mainNavState.push(origin.toDetailsNavKey())
        PendingDetailsNavigation.set(origin, sharedElementKey)
    }

    override fun openSearch(request: SearchNavigationRequest) {
        mainNavState.pushOrReplaceCurrentTopSearch(
            SearchNavKey(
                query = request.query,
                kind = request.kind.name,
                sourceTypes = request.sourceTypes.joinToString(",") { it.name },
                contentKinds = request.contentKinds.joinToString(",") { it.name },
                advancedTitle = request.advancedQuery?.title.orEmpty(),
                advancedTags = request.advancedQuery?.tags.orEmpty(),
                advancedAuthor = request.advancedQuery?.author.orEmpty(),
                pinnedOnly = request.pinnedOnly,
                hideEmpty = request.hideEmpty,
            ),
        )
    }

    override fun pop(): Boolean = mainNavState.pop()
}

private fun DetailsOrigin.toDetailsNavKey(): DetailsNavKey = when (this) {
    is DetailsOrigin.EntityGraph -> DetailsNavKey(
        entityId = entityId,
        requestedProjectionId = initialProjectionLocalMangaId ?: preferredLocalMangaId,
    )
    is DetailsOrigin.LocalMangaId -> DetailsNavKey(requestedProjectionId = mangaId)
    is DetailsOrigin.LocalMangaContent -> DetailsNavKey(requestedProjectionId = manga.id)
    is DetailsOrigin.TrackingEntity,
    is DetailsOrigin.TrackingItem,
    -> DetailsNavKey()
}
