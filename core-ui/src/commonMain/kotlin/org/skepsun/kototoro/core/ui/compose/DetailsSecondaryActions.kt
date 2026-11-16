package org.skepsun.kototoro.core.ui.compose

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.skepsun.kototoro.core.ui.buttons.FilledTonalButton

/**
 * What a details page's secondary action is *for*.
 *
 * The page has one prominent action (read / continue); everything else is secondary and belongs to a task. Grouping
 * them keeps a flat button list from becoming a menu nobody reads: [READING] changes how the work is opened,
 * [LIBRARY] changes what is kept, [REFERENCE] hands the work to something outside the app.
 */
enum class DetailsActionGroup {
    READING,
    LIBRARY,
    REFERENCE,
}

/**
 * One secondary action. [key] is what the host gets back on click, [selected] is the state the action currently holds
 * (favourite, incognito mode), so the label can describe the state instead of an imperative that has to be undone.
 */
@Immutable
data class DetailsSecondaryAction(
    val key: String,
    val label: String,
    val group: DetailsActionGroup,
    val enabled: Boolean = true,
    val selected: Boolean = false,
)

/** Reads the group an action belongs to without depending on the chip's text. */
val DetailsActionGroupKey = SemanticsPropertyKey<DetailsActionGroup>("DetailsActionGroup")

/** Reads whether the action is currently in effect. */
val DetailsActionSelectedKey = SemanticsPropertyKey<Boolean>("DetailsActionSelected")

/**
 * The details page's secondary actions, ordered by task and drawn as one equal-width row.
 *
 * Ordering is stable by [DetailsActionGroup] first, because "how do I read this" and "do I keep this" are answered in
 * that order; the caller's order is kept inside a group.
 */
@Composable
fun DetailsSecondaryActionRow(
    actions: List<DetailsSecondaryAction>,
    onAction: (String) -> Unit,
    modifier: Modifier = Modifier,
    tagPrefix: String = "details-action",
) {
    val ordered = actions.sortedBy { it.group.ordinal }
    Row(
        modifier.fillMaxWidth().heightIn(min = 48.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ordered.forEach { action ->
            FilledTonalButton(
                onClick = { onAction(action.key) },
                enabled = action.enabled,
                colors = if (action.selected) {
                    ButtonDefaults.filledTonalButtonColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    )
                } else {
                    ButtonDefaults.filledTonalButtonColors()
                },
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 48.dp)
                    // Dash, not colon: the details page's tags have always been `preview-favourite`/`preview-incognito`,
                    // and every probe and UI test on both hosts looks them up by that name.
                    .testTag("$tagPrefix-${action.key}")
                    .semantics(mergeDescendants = true) {
                        this[DetailsActionGroupKey] = action.group
                        this[DetailsActionSelectedKey] = action.selected
                    },
            ) {
                Text(action.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}
