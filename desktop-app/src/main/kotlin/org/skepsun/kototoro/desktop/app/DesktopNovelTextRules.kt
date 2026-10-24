package org.skepsun.kototoro.desktop.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.skepsun.kototoro.core.replace.DEFAULT_REPLACE_RULE_TIMEOUT_MS
import org.skepsun.kototoro.core.replace.ReplaceRule
import org.skepsun.kototoro.desktop.runtime.NovelBlock
import org.skepsun.kototoro.desktop.runtime.NovelRun

/**
 * Android's `NovelTextProcessor` for the Windows novel reader.
 *
 * Android hands whole chapter text to the rules, one rule at a time, each under its own timeout; the Windows reader has
 * already parsed the chapter into styled blocks, so a rule runs over every text block in one pass and keeps the block
 * structure (a paragraph that a rule rewrote loses its inline styling, which is what a rewritten paragraph means). A
 * rule that times out or throws leaves the text as the previous rule left it, exactly like Android.
 */
internal object DesktopNovelTextRules {

    /** Rules on a stored list that both apply to [scope] and match this book; disabled rules never run. */
    fun forTarget(rules: List<ReplaceRule>, target: ReplaceRule.Scope, scopeValues: List<String>): List<ReplaceRule> =
        rules.filter { rule -> rule.isEnabled && rule.appliesTo(target, scopeValues) }

    suspend fun blocks(blocks: List<NovelBlock>, rules: List<ReplaceRule>): List<NovelBlock> {
        if (rules.isEmpty() || blocks.isEmpty()) return blocks
        var current = blocks
        for (rule in rules) {
            val next = runRule(rule) { current.map { block -> block.replaced(rule) } }
            if (next != null) current = next
        }
        return current
    }

    suspend fun titles(titles: List<String>, rules: List<ReplaceRule>): List<String> {
        if (rules.isEmpty() || titles.isEmpty()) return titles
        var current = titles
        for (rule in rules) {
            val next = runRule(rule) { current.map { title -> rule.apply(title) } }
            if (next != null) current = next
        }
        return current
    }

    /** Android's per-rule contract: a slow or broken pattern skips that rule, not the chapter. */
    private suspend fun <T> runRule(rule: ReplaceRule, block: () -> T): T? {
        val timeout = if (rule.timeoutMillisecond > 0) rule.timeoutMillisecond else DEFAULT_REPLACE_RULE_TIMEOUT_MS
        return withContext(Dispatchers.Default) {
            withTimeoutOrNull(timeout) { runCatching(block).getOrNull() }
        }
    }

    private fun NovelBlock.replaced(rule: ReplaceRule): NovelBlock = when (this) {
        is NovelBlock.Paragraph -> {
            val replaced = rule.apply(text)
            if (replaced == text) this else NovelBlock.Paragraph(listOf(NovelRun(replaced)))
        }

        is NovelBlock.Heading -> rule.apply(text).takeIf { it != text }?.let { NovelBlock.Heading(level, it) } ?: this
        is NovelBlock.Image, NovelBlock.Rule -> this
    }
}
