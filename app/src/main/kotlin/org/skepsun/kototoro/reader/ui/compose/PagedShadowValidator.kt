package org.skepsun.kototoro.reader.ui.compose

import android.util.Log
import org.skepsun.kototoro.reader.core.PageGeometryHint
import org.skepsun.kototoro.reader.core.PageId
import org.skepsun.kototoro.reader.core.PagedPageSpec
import org.skepsun.kototoro.reader.core.PagedSpreadConfig
import org.skepsun.kototoro.reader.core.PagedSpreadResolver
import org.skepsun.kototoro.reader.core.SceneReadingDirection
import org.skepsun.kototoro.reader.ui.pager.ReaderPage

/**
 * Diagnostic discrepancy between legacy pager spread resolution and modern PagedScene resolution.
 */
data class ShadowDiscrepancy(
    val reason: String,
    val index: Int,
    val legacyPageKeys: List<Long>,
    val scenePageKeys: List<Long>,
    val legacyAnchorKey: Long?,
    val sceneAnchorKey: Long?,
)

/**
 * Shadow mode parity validator verifying mathematical and semantic equivalence
 * between Legacy (DoublePageSpreadModel + PagerState) and PagedScene (PagedSpreadResolver + PagedReaderScene).
 *
 * Implements ADR 0002 Phase 3B:
 * Non-intrusively samples and validates that page pairing, chapter boundary isolation,
 * cover offsets, and progress anchors remain 100% identical.
 */
object PagedShadowValidator {

    private const val TAG = "PagedShadowValidator"

    /**
     * Compares the double-page spreads produced by the Legacy model against PagedSpreadResolver.
     * Returns a list of any detected discrepancies (empty if 100% parity achieved).
     */
    fun validateDoublePageParity(
        pages: List<ReaderPage>,
        coverPage: Boolean,
        reverseLayout: Boolean = false,
        viewportWidth: Int = 1600,
        viewportHeight: Int = 1200,
    ): List<ShadowDiscrepancy> {
        if (pages.isEmpty()) return emptyList()

        // 1. Legacy computation
        val displayItems = buildDoublePageDisplayItems(pages, coverPage = coverPage)
        val legacySpreadModel = DoublePageSpreadModel.create(displayItems.size)
        val legacySpreads = legacySpreadModel.spreads.map { spread ->
            val visiblePages = spread.positions.mapNotNull { displayItems[it].page }
            visiblePages.map { it.readerKey }
        }

        // 2. Scene computation
        val specs = pages.map { page ->
            PagedPageSpec(
                pageId = PageId(page.readerKey),
                geometryHint = PageGeometryHint.Exact(800, 1200),
                chapterId = page.chapterId,
                chapterPageIndex = page.index,
            )
        }
        val direction = if (reverseLayout) SceneReadingDirection.RIGHT_TO_LEFT else SceneReadingDirection.LEFT_TO_RIGHT
        val sceneSlots = PagedSpreadResolver.resolveSlots(
            specs = specs,
            viewportWidth = viewportWidth,
            viewportHeight = viewportHeight,
            config = PagedSpreadConfig(
                isDoublePage = true,
                isCoverOffset = coverPage,
                readingDirection = direction,
            ),
        )

        val discrepancies = mutableListOf<ShadowDiscrepancy>()

        if (legacySpreads.size != sceneSlots.size) {
            discrepancies.add(
                ShadowDiscrepancy(
                    reason = "Spread count mismatch: legacy=${legacySpreads.size}, scene=${sceneSlots.size}",
                    index = -1,
                    legacyPageKeys = emptyList(),
                    scenePageKeys = emptyList(),
                    legacyAnchorKey = null,
                    sceneAnchorKey = null,
                ),
            )
            return discrepancies
        }

        for (i in legacySpreads.indices) {
            val legacyKeys = legacySpreads[i]
            val sceneSlot = sceneSlots[i]
            val sceneKeys = sceneSlot.pageIds.map { it.value }

            val legacyAnchor = legacyKeys.firstOrNull()
            val sceneAnchor = sceneSlot.progressAnchorPageId.value

            if (legacyKeys != sceneKeys) {
                discrepancies.add(
                    ShadowDiscrepancy(
                        reason = "Page keys mismatch at spread/slot index $i",
                        index = i,
                        legacyPageKeys = legacyKeys,
                        scenePageKeys = sceneKeys,
                        legacyAnchorKey = legacyAnchor,
                        sceneAnchorKey = sceneAnchor,
                    ),
                )
            } else if (legacyAnchor != sceneAnchor) {
                discrepancies.add(
                    ShadowDiscrepancy(
                        reason = "Anchor key mismatch at spread/slot index $i: legacy=$legacyAnchor, scene=$sceneAnchor",
                        index = i,
                        legacyPageKeys = legacyKeys,
                        scenePageKeys = sceneKeys,
                        legacyAnchorKey = legacyAnchor,
                        sceneAnchorKey = sceneAnchor,
                    ),
                )
            }
        }

        return discrepancies
    }

    /**
     * Runtime observer method for lightweight sampling in debug or test environments.
     */
    fun sampleRuntimeParity(
        pages: List<ReaderPage>,
        coverPage: Boolean,
        reverseLayout: Boolean,
    ) {
        val issues = validateDoublePageParity(pages, coverPage, reverseLayout)
        if (issues.isNotEmpty()) {
            for (issue in issues) {
                Log.w(TAG, "Shadow parity divergence detected: ${issue.reason}")
            }
        }
    }
}
