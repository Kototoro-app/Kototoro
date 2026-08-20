package org.skepsun.kototoro.home.ui.compose.hero

import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import java.util.Locale
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.ui.compose.contentCoverSharedKey
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

internal val HomeHeroEntry.sharedElementKey: String
    get() = contentCoverSharedKey(
        sourceName = content.source.name,
        url = content.coverUrl.orEmpty(),
        instanceKey = "home_hero_${kind.name.lowercase(Locale.ROOT)}_${content.id}",
    )

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
): List<HomeHeroEntry> {
    val entries = ArrayList<HomeHeroEntry>(HOME_HERO_TOTAL_LIMIT)

    fun addEntry(entry: HomeHeroEntry) {
        if (entries.size >= HOME_HERO_TOTAL_LIMIT) return
        entries += entry
    }

    resumeContent?.let { content ->
        addEntry(
                HomeHeroEntry(
                    kind = HomeHeroKind.RESUME,
                    content = content,
                    groupKey = resumeGroupKey ?: content.id,
                    progressPercent = resumeProgressPercent,
                ),
            )
        }

    historyItems
        .asSequence()
        .filterNot { it.groupKey == resumeGroupKey }
        .take(HOME_HERO_HISTORY_LIMIT)
        .forEach { item ->
            addEntry(
                HomeHeroEntry(
                    kind = HomeHeroKind.HISTORY,
                    content = item.content,
                    groupKey = item.groupKey,
                ),
            )
        }

    updateItems
        .asSequence()
        .filterNot { it.groupKey == resumeGroupKey }
        .take(HOME_HERO_UPDATES_LIMIT)
        .forEach { item ->
            addEntry(
                HomeHeroEntry(
                    kind = HomeHeroKind.UPDATE,
                    content = item.content,
                    groupKey = item.groupKey,
                    newChapters = item.newChapters,
                ),
            )
        }

    recommendationItems
        .asSequence()
        .filterNot { it.groupKey == resumeGroupKey }
        .take(HOME_HERO_RECOMMENDATIONS_LIMIT)
        .forEach { item ->
            addEntry(
                HomeHeroEntry(
                    kind = HomeHeroKind.RECOMMENDATION,
                    content = item.content,
                    groupKey = item.groupKey,
                ),
            )
        }

    return entries
}

