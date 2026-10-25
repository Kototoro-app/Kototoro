package org.skepsun.kototoro.ios

import org.skepsun.kototoro.core.library.ReplaceRuleStore
import org.skepsun.kototoro.core.replace.ReplaceRule
import org.skepsun.kototoro.core.replace.applyReplaceRules
import org.skepsun.kototoro.reader.novel.NovelDocumentBlock

/**
 * Android's novel text replacement rules on iOS: the shared `replace_rules` table and store (also Windows'), Legado
 * JSON import/export, and the shared rule semantics (order, per-rule timeout, failed rules skipped).
 */
class IosReplaceRules internal constructor(private val store: ReplaceRuleStore) {

    suspend fun all(): List<ReplaceRule> = store.all()

    suspend fun save(rule: ReplaceRule): List<ReplaceRule> = store.save(rule)

    suspend fun setEnabled(rule: ReplaceRule, enabled: Boolean): List<ReplaceRule> = store.setEnabled(rule, enabled)

    suspend fun setAllEnabled(enabled: Boolean): List<ReplaceRule> = store.setAllEnabled(enabled)

    suspend fun delete(id: Long): List<ReplaceRule> = store.delete(id)

    /** How many rules [json] (Legado's shape) added or replaced; 0 for a document that cannot be read. */
    suspend fun importJson(json: String): Int = store.importFromJson(json)

    suspend fun exportJson(): String = store.exportToJson()

    /** [document] with the content rules for [content] applied to its lines (Android scopes by title and source). */
    suspend fun apply(document: IosNovelDocument, content: IosSourceContent): IosNovelDocument {
        val rules = store.forBook(content.title, content.sourceName).filter { it.scopeContent }
        if (rules.none { it.isEnabled }) return document
        val lines = document.lines.withIndex().mapNotNull { (index, block) ->
            (block as? NovelDocumentBlock.Text)?.let { index to it.original }
        }
        if (lines.isEmpty()) return document
        // One pass over the chapter keeps each rule's timeout per chapter rather than per line; a rule that adds
        // or removes line breaks makes the lines no longer line up, and then each line is replaced on its own.
        val joined = applyReplaceRules(lines.joinToString("\n") { it.second }, rules).text.split('\n')
        val replaced = if (joined.size == lines.size) {
            lines.indices.associate { lines[it].first to joined[it] }
        } else {
            lines.associate { (index, text) -> index to applyReplaceRules(text, rules).text }
        }
        return document.withLines(replaced)
    }

    /** [title] after the chapter-title rules for [content]. */
    suspend fun title(title: String, content: IosSourceContent): String {
        val rules = store.forBook(content.title, content.sourceName).filter { it.scopeTitle }
        return if (rules.none { it.isEnabled }) title else applyReplaceRules(title, rules).text
    }

    companion object {
        /** A rule as the editor builds it; [id] 0 adds one. */
        fun rule(id: Long, name: String, group: String?, pattern: String, replacement: String, isRegex: Boolean,
                 isEnabled: Boolean, scopeTitle: Boolean, scopeContent: Boolean, scopeFilter: String?,
                 excludeScope: String?, order: Int, timeoutMillisecond: Long): ReplaceRule =
            ReplaceRule(id = id, name = name, group = group?.takeIf(String::isNotBlank), pattern = pattern,
                replacement = replacement, scopeFilter = scopeFilter?.takeIf(String::isNotBlank),
                scopeTitle = scopeTitle, scopeContent = scopeContent,
                excludeScope = excludeScope?.takeIf(String::isNotBlank), isEnabled = isEnabled, isRegex = isRegex,
                timeoutMillisecond = timeoutMillisecond, order = order)

        /** The editor's live preview: [sample] after [rule] alone, or null while the rule is invalid. */
        fun preview(rule: ReplaceRule, sample: String): String? = if (rule.isValid()) rule.apply(sample) else null
    }
}
