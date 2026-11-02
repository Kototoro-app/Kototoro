package org.skepsun.kototoro.desktop.app

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.skepsun.kototoro.core.replace.ReplaceRule
import java.nio.file.Files
import java.nio.file.Path

/** What the replace-rule page draws: the stored rules once read, and the editor dialog's subject. */
internal data class DesktopReplaceRulesUi(
    /** Null until the table has been read; an empty list is a real answer. */
    val rules: List<ReplaceRule>? = null,
    /** The dialog is open; [editing] is null for "new rule". */
    val editorOpen: Boolean = false,
    val editing: ReplaceRule? = null,
)

/**
 * Android's replace-rule screens (`ReplaceRulesSettingsScreen` + `ReplaceRuleSettingsViewModel`) over the shared
 * `replace_rules` table.
 *
 * The rules live in its own flow rather than in [DesktopAppState]: they belong to one page, the same way the crash-log
 * list does, and every write comes back through the Room flow, so the page never guesses what the table now holds.
 */
internal class DesktopReplaceRuleOperations(private val scope: CoroutineScope, session: DesktopSession) {
    private val rules = session.replaceRules
    private val mutable = MutableStateFlow(DesktopReplaceRulesUi())

    val state: StateFlow<DesktopReplaceRulesUi> = mutable.asStateFlow()

    /** Android's screen observes the table for as long as it is open; [watch] is idempotent. */
    fun open() {
        watch()
    }

    private fun watch() {
        if (watching) return
        watching = true
        scope.launch {
            rules.observeAll().collect { stored ->
                mutable.update { it.copy(rules = stored.sortedBy { rule -> rule.order }) }
            }
        }
    }

    fun openEditor(rule: ReplaceRule?) {
        mutable.update { it.copy(editorOpen = true, editing = rule) }
    }

    fun closeEditor() {
        mutable.update { it.copy(editorOpen = false, editing = null) }
    }

    fun save(rule: ReplaceRule) {
        scope.launch {
            withContext(Dispatchers.IO) { rules.save(rule) }
            closeEditor()
        }
    }

    fun delete(rule: ReplaceRule) {
        scope.launch {
            withContext(Dispatchers.IO) { rules.delete(rule.id) }
            closeEditor()
        }
    }

    fun setEnabled(rule: ReplaceRule, enabled: Boolean) {
        scope.launch { withContext(Dispatchers.IO) { rules.setEnabled(rule, enabled) } }
    }

    fun setAllEnabled(enabled: Boolean) {
        scope.launch { withContext(Dispatchers.IO) { rules.setAllEnabled(enabled) } }
    }

    /** Android's SAF import: the file's text is decoded by the shared codec, 0 means the file was not a rule list. */
    suspend fun import(raw: String): Int = withContext(Dispatchers.IO) { rules.importFromJson(raw) }

    suspend fun import(source: Path): Int = withContext(Dispatchers.IO) {
        runCatching { Files.readString(source) }.getOrNull()?.let { rules.importFromJson(it) } ?: 0
    }

    /** Android's export writes `kototoro-replace-rules.json` to the chosen document; here the caller picked the path. */
    suspend fun export(target: Path): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            Files.createDirectories(target.toAbsolutePath().parent)
            Files.writeString(target, rules.exportToJson())
        }.isSuccess
    }

    private var watching = false
}
