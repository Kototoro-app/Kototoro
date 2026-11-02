package org.skepsun.kototoro.desktop.app

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import org.skepsun.kototoro.core.dictionary.DictionaryRule
import org.skepsun.kototoro.core.ui.settings.DictionaryRuleEditorDialog
import org.skepsun.kototoro.core.ui.settings.DictionaryRuleUiModel
import org.skepsun.kototoro.core.ui.settings.DictionaryRulesContent
import java.io.File
import javax.swing.JFileChooser
import javax.swing.filechooser.FileNameExtensionFilter

/** The host owns file selection, table observation and successful-save dismissal; cards and drafts are shared. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun DesktopDictionaryRulesPage(controller: DesktopController, state: DesktopAppState) {
    val ui by controller.dictionaryRules.collectAsState()
    val active = !state.busy
    val rules = ui.rules
    Column(Modifier.widthIn(max = 820.dp).fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            OutlinedButton({ importDictionaryRules(controller) }, enabled = active,
                modifier = Modifier.testTag("dictionary-rules-import")) { Text(AndroidStrings["import_button"]) }
            OutlinedButton({ exportDictionaryRules(controller) }, enabled = active && !rules.isNullOrEmpty(),
                modifier = Modifier.testTag("dictionary-rules-export")) { Text(AndroidStrings["export"]) }
            OutlinedButton({ controller.openDictionaryRuleEditor(null) }, enabled = active,
                modifier = Modifier.testTag("dictionary-rules-add")) { Text(AndroidStrings["add"]) }
        }
        Text(AndroidStrings["dictionary_rules_summary"], style = MaterialTheme.typography.bodySmall, color = Muted,
            modifier = Modifier.testTag("dictionary-rules-summary"))
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        DictionaryRulesContent(
            rules = rules?.map(DictionaryRule::toUiModel),
            onEdit = { item -> controller.openDictionaryRuleEditor(rules?.firstOrNull { it.name == item.name }) },
            onToggle = { item, enabled -> rules?.firstOrNull { it.name == item.name }?.let {
                controller.setDictionaryRuleEnabled(it, enabled)
            } },
            onDelete = { item -> rules?.firstOrNull { it.name == item.name }?.let(controller::deleteDictionaryRule) },
            enabled = active,
            loadingText = desktopText("dictionaryRules.loading"),
            modifier = Modifier.weight(1f),
        )
    }
    if (ui.editorOpen) {
        DictionaryRuleEditorDialog(
            initial = ui.editing?.toUiModel(),
            onDismiss = controller::closeDictionaryRuleEditor,
            onSave = { controller.saveDictionaryRule(DictionaryRule(it.name, it.urlRule, it.showRule, it.enabled,
                it.sortNumber.trim().toIntOrNull() ?: 0)) },
            onDelete = ui.editing?.let { rule -> { controller.deleteDictionaryRule(rule) } },
            enabled = active,
        )
    }
}

private fun DictionaryRule.toUiModel() = DictionaryRuleUiModel(name, urlRule, showRule, enabled, sortNumber.toString())

/** Android's SAF create/open document, replaced by the host's file chooser; the codec and file name are shared. */
private fun importDictionaryRules(controller: DesktopController) {
    val chooser = JFileChooser().apply {
        fileFilter = FileNameExtensionFilter(desktopText("dictionaryRules.filter"), "json")
    }
    if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
        controller.importDictionaryRules(chooser.selectedFile.toPath())
    }
}

private fun exportDictionaryRules(controller: DesktopController) {
    val chooser = JFileChooser().apply {
        fileFilter = FileNameExtensionFilter(desktopText("dictionaryRules.filter"), "json")
        selectedFile = File("kototoro-dictionary-rules.json")
    }
    if (chooser.showSaveDialog(null) == JFileChooser.APPROVE_OPTION) {
        controller.exportDictionaryRules(chooser.selectedFile.toPath())
    }
}
