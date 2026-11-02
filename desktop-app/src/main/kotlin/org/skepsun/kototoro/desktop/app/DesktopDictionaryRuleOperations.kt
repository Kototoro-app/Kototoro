package org.skepsun.kototoro.desktop.app

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.skepsun.kototoro.core.dictionary.DictionaryRule
import java.nio.file.Files
import java.nio.file.Path

/** What the dictionary-rule page draws: the stored rules once read, and the editor dialog's subject. */
internal data class DesktopDictionaryRulesUi(
    /** Null until the table has been read; an empty list is a real answer. */
    val rules: List<DictionaryRule>? = null,
    /** The dialog is open; [editing] is null for "new rule". */
    val editorOpen: Boolean = false,
    val editing: DictionaryRule? = null,
)

/**
 * Android's `DictionaryRulesActivity` over the shared `dictionary_rules` table.
 *
 * Like the replace-rule operations, the rules stay in their own flow instead of [DesktopAppState]: one page reads them
 * and every write comes back through the Room flow, so the page never has to guess what the table holds.
 */
internal class DesktopDictionaryRuleOperations(private val scope: CoroutineScope, session: DesktopSession) {
    private val rules = session.dictionaryRules
    private val mutable = MutableStateFlow(DesktopDictionaryRulesUi())

    val state: StateFlow<DesktopDictionaryRulesUi> = mutable.asStateFlow()

    fun open() {
        watch()
    }

    private fun watch() {
        if (watching) return
        watching = true
        scope.launch {
            rules.observeAll().collect { stored ->
                mutable.update { it.copy(rules = stored) }
            }
        }
    }

    fun openEditor(rule: DictionaryRule?) {
        mutable.update { it.copy(editorOpen = true, editing = rule) }
    }

    fun closeEditor() {
        mutable.update { it.copy(editorOpen = false, editing = null) }
    }

    fun save(rule: DictionaryRule) {
        scope.launch {
            withContext(Dispatchers.IO) { rules.save(rule) }
            closeEditor()
        }
    }

    fun delete(rule: DictionaryRule) {
        scope.launch {
            withContext(Dispatchers.IO) { rules.delete(rule) }
            closeEditor()
        }
    }

    fun setEnabled(rule: DictionaryRule, enabled: Boolean) {
        scope.launch { withContext(Dispatchers.IO) { rules.setEnabled(rule, enabled) } }
    }

    /** Android's SAF import decodes the document with the shared codec; 0 means it carried no usable rule. */
    suspend fun import(raw: String): Int = withContext(Dispatchers.IO) { rules.importFromJson(raw) }

    suspend fun import(source: Path): Int = withContext(Dispatchers.IO) {
        runCatching { Files.readString(source) }.getOrNull()?.let { rules.importFromJson(it) } ?: 0
    }

    /** Android exports `dictionary-rules.json` to the chosen document; here the caller picked the path already. */
    suspend fun export(target: Path): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            Files.createDirectories(target.toAbsolutePath().parent)
            Files.writeString(target, rules.exportToJson())
        }.isSuccess
    }

    private var watching = false
}
