package org.skepsun.kototoro.desktop.app

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.skepsun.kototoro.core.details.DetailsChapterRef
import org.skepsun.kototoro.core.details.DetailsPrimaryAction
import org.skepsun.kototoro.core.details.DetailsPrimaryActionKind
import org.skepsun.kototoro.core.source.SourceChapter
import org.skepsun.kototoro.core.source.SourceContent
import org.skepsun.kototoro.core.ui.buttons.Button
import org.skepsun.kototoro.core.ui.compose.DetailsActionGroup
import org.skepsun.kototoro.core.ui.compose.DetailsSecondaryAction
import org.skepsun.kototoro.core.ui.chapters.chaptersOfBranch
import org.skepsun.kototoro.core.ui.compose.DetailsSecondaryActionRow

/**
 * The favourites action reads as an action (加入收藏 / 取消收藏), matching the wording this page already used, while
 * [DetailsSecondaryAction.selected] makes the current state visible on the button itself.
 */
internal fun desktopFavouriteLabel(isFavourite: Boolean): String =
    desktopText(if (isFavourite) "details.unfavourite" else "details.favourite")

/** The branch's chapters plus the shared primary-action decision for the open work. */
@Composable
internal fun rememberDesktopDetailsPrimary(
    content: SourceContent,
    state: DesktopAppState,
): DesktopPrimaryContext {
    val chapters = remember(content.chapters, state.chapterBranch) {
        chaptersOfBranch(content.chapters.orEmpty(), SourceChapter::branch, state.chapterBranch)
    }
    val action = remember(chapters, state.detailsChapterId, state.detailsFurthestChapterId) {
        desktopDetailsPrimaryAction(
            chapters = chapters.map { DetailsChapterRef(it.id, desktopChapterTitle(it)) },
            currentChapterId = state.detailsChapterId,
            readThroughChapterId = state.detailsFurthestChapterId,
        )
    }
    return DesktopPrimaryContext(chapters = chapters, action = action)
}

/** The chapters the action can open, so the button can hand the reader the exact chapter it named. */
internal class DesktopPrimaryContext(
    val chapters: List<SourceChapter>,
    val action: DetailsPrimaryAction,
)

/**
 * The details page's one prominent action. Every details style draws this instead of its own "read" button, so the
 * wording, the enabled state and the chapter it opens cannot drift apart between the hero and the summary column.
 */
@Composable
internal fun DesktopDetailsPrimaryButton(
    controller: DesktopController,
    state: DesktopAppState,
    content: SourceContent,
    modifier: Modifier = Modifier,
    shape: Shape? = null,
    minHeight: Dp = 48.dp,
    fillWidth: Boolean = true,
) {
    val primary = rememberDesktopDetailsPrimary(content, state)
    val isVideo = DesktopReaderKind.of(content) == DesktopReaderKind.VIDEO
    val sized = (if (fillWidth) modifier.fillMaxWidth() else modifier).heightIn(min = minHeight)
    val onClick: () -> Unit = {
        // CONTINUE means "pick up where the history left off", so the chapter is left to the controller: handing it
        // an explicit chapter restarts that chapter from its first page and silently drops the saved position.
        val target = primary.chapters.firstOrNull { it.id == primary.action.chapterId }
        controller.read(target.takeIf { primary.action.kind != DetailsPrimaryActionKind.CONTINUE })
        Unit
    }
    val label = desktopDetailsPrimaryLabel(primary.action, isVideo)
    if (shape == null) {
        Button(onClick, enabled = !state.busy && primary.action.isAvailable,
            modifier = sized.testTag("preview-read")) {
            Text(label)
        }
    } else {
        Button(onClick, enabled = !state.busy && primary.action.isAvailable, shape = shape,
            modifier = sized.testTag("preview-read")) {
            Text(label)
        }
    }
}

/**
 * The secondary actions, grouped by the task they belong to: how this work gets read, then whether it is kept.
 * [tagPrefix] lets each details style keep the test tag its probes already use.
 */
@Composable
internal fun DesktopDetailsSecondaryRow(
    controller: DesktopController,
    state: DesktopAppState,
    content: SourceContent,
    modifier: Modifier = Modifier,
    tagPrefix: String = "preview",
) {
    val actions = buildList {
        // Present for every work, hidden only while the reader already is incognito.
        if (!state.readerIncognito) {
            add(
                DetailsSecondaryAction(
                    key = "incognito",
                    label = AndroidStrings["incognito"],
                    group = DetailsActionGroup.READING,
                    enabled = !state.busy && !content.chapters.isNullOrEmpty(),
                ),
            )
        }
        add(
            DetailsSecondaryAction(
                key = "favourite",
                label = desktopFavouriteLabel(state.isFavourite),
                group = DetailsActionGroup.LIBRARY,
                enabled = !state.busy,
                selected = state.isFavourite,
            ),
        )
    }
    DetailsSecondaryActionRow(
        actions = actions,
        onAction = { key ->
            when (key) {
                "favourite" -> {
                    controller.toggleFavourite()
                    Unit
                }
                "incognito" -> {
                    controller.readIncognito()
                    Unit
                }
            }
        },
        modifier = modifier,
        tagPrefix = tagPrefix,
    )
}
