package org.skepsun.kototoro.desktop.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import org.skepsun.kototoro.core.replace.ReplaceRule
import org.skepsun.kototoro.core.ui.settings.ReplaceRuleEditorDialog
import org.skepsun.kototoro.core.ui.settings.ReplaceRuleUiModel
import org.skepsun.kototoro.core.ui.settings.ReplaceRulesContent
import java.io.File
import javax.swing.JFileChooser
import javax.swing.filechooser.FileNameExtensionFilter

/**
 * Desktop host for the shared replace-rule page: [ReplaceRulesContent] draws Android's action group, the grouped rule
 * list and the empty/loading states, and [ReplaceRuleEditorDialog] draws Android's editor with the live preview.
 *
 * What stays here is what the platform owns: the back button, the table observation through the controller, and the
 * file chooser that replaces Android's SAF document picker — the JSON codec itself is the shared repository call.
 */
@Composable
internal fun DesktopReplaceRulesPage(controller: DesktopController, state: DesktopAppState) {
    val ui by controller.replaceRules.collectAsState()
    val active = !state.busy
    // Null means the table has not been read yet: an empty list is a real answer, "not asked" is not.
    val rules = ui.rules
    Column(
        Modifier.widthIn(max = 820.dp).fillMaxSize().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ReplaceRulesContent(
            rules = rules?.map(ReplaceRule::toUiModel),
            onAdd = { controller.openReplaceRuleEditor(null) },
            onEdit = { item -> rules?.firstOrNull { it.id == item.id }?.let(controller::openReplaceRuleEditor) },
            onToggle = { item, enabled ->
                rules?.firstOrNull { it.id == item.id }?.let { controller.setReplaceRuleEnabled(it, enabled) }
            },
            onSetAllEnabled = controller::setAllReplaceRulesEnabled,
            onImport = { importReplaceRules(controller) },
            onExport = { exportReplaceRules(controller) },
            enabled = active,
            loadingText = desktopText("replaceRules.loading"),
        )
    }
    if (ui.editorOpen) {
        ReplaceRuleEditorDialog(
            initial = ui.editing?.toUiModel(),
            onDismiss = controller::closeReplaceRuleEditor,
            // The editor closes after the write lands, not when the button is pressed.
            onSave = { controller.saveReplaceRule(it.toRule()) },
            isValid = { it.toRule().isValid() },
            preview = { item, text -> item.toRule().apply(text) },
            onDelete = ui.editing?.let { rule -> { controller.deleteReplaceRule(rule) } },
            enabled = active,
        )
    }
}

/** The stored row as the shared page shows it; the nullable columns become blank strings. */
private fun ReplaceRule.toUiModel() = ReplaceRuleUiModel(
    id = id,
    name = name,
    group = group.orEmpty(),
    pattern = pattern,
    replacement = replacement,
    scopeFilter = scopeFilter.orEmpty(),
    excludeScope = excludeScope.orEmpty(),
    isRegex = isRegex,
    scopeTitle = scopeTitle,
    scopeContent = scopeContent,
    isEnabled = isEnabled,
    order = order,
    timeoutMillisecond = timeoutMillisecond,
)

/** Back to the entity the repository stores, keeping the blank-to-null mapping for the optional columns. */
private fun ReplaceRuleUiModel.toRule() = ReplaceRule(
    id = id,
    name = name,
    group = group.ifBlank { null },
    pattern = pattern,
    replacement = replacement,
    scopeFilter = scopeFilter.ifBlank { null },
    scopeTitle = scopeTitle,
    scopeContent = scopeContent,
    excludeScope = excludeScope.ifBlank { null },
    isEnabled = isEnabled,
    isRegex = isRegex,
    timeoutMillisecond = timeoutMillisecond,
    order = order,
)

/** Android's SAF create/open document, replaced by the host's file chooser; the codec and file name are shared. */
private fun importReplaceRules(controller: DesktopController) {
    val chooser = JFileChooser().apply {
        fileFilter = FileNameExtensionFilter(desktopText("replaceRules.filter"), "json")
    }
    if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
        controller.importReplaceRules(chooser.selectedFile.toPath())
    }
}

private fun exportReplaceRules(controller: DesktopController) {
    val chooser = JFileChooser().apply {
        fileFilter = FileNameExtensionFilter(desktopText("replaceRules.filter"), "json")
        selectedFile = File("kototoro-replace-rules.json")
    }
    if (chooser.showSaveDialog(null) == JFileChooser.APPROVE_OPTION) {
        controller.exportReplaceRules(chooser.selectedFile.toPath())
    }
}
