package org.skepsun.kototoro.core.ui.wizard

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import org.skepsun.kototoro.core.ui.adaptive.tvFocusable
import org.skepsun.kototoro.core.ui.text.uiString
import org.skepsun.kototoro.reader.ui.compose.design.ReaderPanelIcons

/**
 * Android's setup wizard pages (`main/ui/welcome`) that a desktop host can honour as well: the intro that names what a
 * repository, an extension and a source are, and the done page with its two ways on. The phone's remaining pages stay
 * on the phone — the permissions page grants Android runtime permissions, and the spaces page configures the Android
 * library switcher — so this module only carries the two pages whose content is a fact about Kototoro rather than about
 * the platform that draws it.
 *
 * Everything here is string-driven from Android's own resource names, so both hosts say exactly what the phone says;
 * [WizardPageHeader] and [WizardSectionCard] are Android's shared chrome for a wizard page, and the hosts' own page
 * chrome (a back button, the step navigation, dialogs) stays with the host.
 */
@Composable
fun WizardPageHeader(
    step: Int,
    totalSteps: Int,
    title: String,
    summary: String,
    modifier: Modifier = Modifier,
    icon: Painter? = null,
    iconVector: ImageVector? = null,
    prominent: Boolean = false,
    containerColor: Color = MaterialTheme.colorScheme.primaryContainer,
    contentColor: Color = MaterialTheme.colorScheme.onPrimaryContainer,
    stepTag: String? = "wizard-step-counter",
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Surface(
            shape = RoundedCornerShape(if (prominent) 22.dp else 16.dp),
            color = containerColor,
            contentColor = contentColor,
        ) {
            val iconModifier = Modifier
                .padding(if (prominent) 14.dp else 12.dp)
                .size(if (prominent) 30.dp else 24.dp)
            when {
                iconVector != null -> Icon(iconVector, contentDescription = null, modifier = iconModifier)
                icon != null -> Icon(icon, contentDescription = null, modifier = iconModifier)
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = uiString("welcome_step_counter", step, totalSteps),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = if (stepTag != null) Modifier.testTag(stepTag) else Modifier,
            )
            // Android slides the title when its pager turns; keeping the animation here means a host that swaps pages
            // in place gets the same motion instead of a hard cut.
            AnimatedContent(
                targetState = title,
                transitionSpec = {
                    (
                        slideInVertically { it / 2 } + fadeIn() togetherWith
                            slideOutVertically { -it / 2 } + fadeOut()
                        )
                },
                label = "wizardTitle",
            ) { animatedTitle ->
                Text(
                    text = animatedTitle,
                    style = if (prominent) {
                        MaterialTheme.typography.headlineMedium
                    } else {
                        MaterialTheme.typography.headlineSmall
                    },
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            Text(
                text = summary,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Android's body container for one wizard section: a Material container with an optional title, summary and trailing
 * actions, then the section's own content. The glass finish stays reserved for the phone's floating action bar.
 */
@Composable
fun WizardSectionCard(
    modifier: Modifier = Modifier,
    title: String? = null,
    summary: String? = null,
    actions: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (title != null || actions != null) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        if (title != null) {
                            Text(
                                text = title,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                        if (summary != null) {
                            Text(
                                text = summary,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    actions?.invoke()
                }
            } else if (summary != null) {
                Text(
                    text = summary,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            content()
        }
    }
}

/**
 * Android's `WelcomeIntroStep`: what a repository, an extension and a source are, then the disclaimer Android shows in
 * its error container. Nothing here is platform-specific, so the phone and a desktop host read the same page.
 */
@Composable
fun WizardIntroPage(
    step: Int,
    totalSteps: Int,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        WizardPageHeader(
            step = step,
            totalSteps = totalSteps,
            title = uiString("welcome_intro_app_title"),
            summary = uiString("welcome_intro_body"),
            iconVector = ReaderPanelIcons.Welcome,
            prominent = true,
        )
        WizardSectionCard(title = uiString("welcome_intro_concepts_title")) {
            WizardConceptItem(uiString("welcome_intro_repo_label"), uiString("welcome_intro_repo_body"))
            WizardConceptItem(
                uiString("welcome_intro_extension_label"),
                uiString("welcome_intro_extension_body"),
            )
            WizardConceptItem(uiString("welcome_intro_source_label"), uiString("welcome_intro_source_body"))
        }
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                text = uiString("welcome_intro_disclaimer"),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(16.dp),
            )
        }
    }
}

@Composable
private fun WizardConceptItem(title: String, summary: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = summary,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Android's `WelcomeDoneStep`: the two ways on from the wizard, then the advice card about sources that arrived broken.
 * The two jumps are callbacks because each host owns the screens behind them.
 *
 * The buttons carry `wizard-open-extensions`/`wizard-restore-backup`, which is how the desktop probe reaches them.
 */
@Composable
fun WizardDonePage(
    step: Int,
    totalSteps: Int,
    onOpenExtensionManagement: () -> Unit,
    onRestoreBackup: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        WizardPageHeader(
            step = step,
            totalSteps = totalSteps,
            title = uiString("welcome_done_title_finished"),
            summary = uiString("welcome_done_summary_finished"),
            iconVector = ReaderPanelIcons.Check,
            prominent = true,
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        )
        WizardSectionCard {
            Text(
                text = uiString("welcome_done_body_finished"),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            WizardJumpButton(
                text = uiString("welcome_open_extension_management"),
                icon = ReaderPanelIcons.Settings,
                tag = "wizard-open-extensions",
                onClick = onOpenExtensionManagement,
            )
        }
        WizardSectionCard(
            title = uiString("restore_backup"),
            summary = uiString("welcome_restore_summary"),
        ) {
            WizardJumpButton(
                text = uiString("restore_backup"),
                icon = ReaderPanelIcons.BackupRestore,
                tag = "wizard-restore-backup",
                onClick = onRestoreBackup,
            )
        }
        WizardSectionCard(
            title = uiString("welcome_done_advice_title"),
            summary = uiString("welcome_done_advice_summary"),
        ) {
            Text(
                text = uiString("welcome_done_advice_body"),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun WizardJumpButton(text: String, icon: ImageVector, tag: String, onClick: () -> Unit) {
    FilledTonalButton(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .testTag(tag)
            // A TV remote has to see where focus is; the ring only observes the button's own focus target.
            .tvFocusable(shape = ButtonDefaults.shape, addFocusTarget = false),
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(text)
    }
}
