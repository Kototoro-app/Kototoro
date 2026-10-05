package org.skepsun.kototoro.home.ui.compose.hero

import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.skepsun.kototoro.R
import org.skepsun.kototoro.home.ui.HOME_HERO_HISTORY_LIMIT
import org.skepsun.kototoro.home.ui.HOME_HERO_RECOMMENDATIONS_LIMIT
import org.skepsun.kototoro.home.ui.HOME_HERO_TOTAL_LIMIT
import org.skepsun.kototoro.home.ui.HOME_HERO_UPDATES_LIMIT
import org.skepsun.kototoro.home.ui.HomeRecentItem
import org.skepsun.kototoro.home.ui.HomeRecommendationItem
import org.skepsun.kototoro.home.ui.HomeUpdateItem
import org.skepsun.kototoro.parsers.model.Content


internal val HOME_HERO_CARD_HEIGHT = 184.dp

internal enum class HomeHeroKind(val labelRes: Int, val iconRes: Int) {
    RESUME(R.string.home_resume_title, R.drawable.ic_read),
    HISTORY(R.string.recent_history, R.drawable.ic_history),
    UPDATE(R.string.home_recent_updates, R.drawable.ic_updated),
    RECOMMENDATION(R.string.suggestions, R.drawable.ic_suggestion),
}

internal data class HomeHeroEntry(
    val kind: HomeHeroKind,
    val content: Content,
    val groupKey: Long,
    val progressPercent: Int? = null,
    val newChapters: Int = 0,
)

// The hero's shared-element key is derived once, in HomeHeroCard, keyed by
// `groupKey`. An earlier `content.id`-based twin of it used to live here: a key
// that never matches its registration silently kills the hero (see c1002dad6),
// so there is deliberately no second derivation.

@Composable
internal fun HomeHeroEntry.supportingText(): String? = when (kind) {
    HomeHeroKind.RESUME -> null
    HomeHeroKind.UPDATE -> newChapters
        .takeIf { it > 0 }
        ?.let { value ->
            stringResource(
                R.string.new_chapters_pattern,
                stringResource(R.string.new_chapters),
                value,
            )
        }
    HomeHeroKind.HISTORY,
    HomeHeroKind.RECOMMENDATION -> null
}

internal fun buildHomeHeroEntries(
    resumeContent: Content?,
    resumeGroupKey: Long?,
    resumeProgressPercent: Int?,
    historyItems: List<org.skepsun.kototoro.home.ui.HomeRecentItem>,
    updateItems: List<org.skepsun.kototoro.home.ui.HomeUpdateItem>,
    recommendationItems: List<org.skepsun.kototoro.home.ui.HomeRecommendationItem>,
): List<HomeHeroEntry> = org.skepsun.kototoro.core.ui.home.buildHomeHeroEntries(
    // The ordering and limits are shared with the Windows host's hero.
    resume = resumeContent,
    resumeGroupKey = resumeGroupKey,
    resumeProgressPercent = resumeProgressPercent,
    resumeKeyOf = Content::id,
    history = historyItems.map { org.skepsun.kototoro.core.ui.home.HomeHeroCandidate(it.content, it.groupKey) },
    updates = updateItems.map { org.skepsun.kototoro.core.ui.home.HomeHeroCandidate(it.content, it.groupKey, it.newChapters) },
    recommendations = recommendationItems.map { org.skepsun.kototoro.core.ui.home.HomeHeroCandidate(it.content, it.groupKey) },
    limits = org.skepsun.kototoro.core.ui.home.HomeHeroLimits(
        total = HOME_HERO_TOTAL_LIMIT,
        history = HOME_HERO_HISTORY_LIMIT,
        updates = HOME_HERO_UPDATES_LIMIT,
        recommendations = HOME_HERO_RECOMMENDATIONS_LIMIT,
    ),
).map { entry ->
    HomeHeroEntry(
        kind = HomeHeroKind.valueOf(entry.kind.name),
        content = entry.content,
        groupKey = entry.groupKey,
        progressPercent = entry.progressPercent,
        newChapters = entry.newChapters,
    )
}

