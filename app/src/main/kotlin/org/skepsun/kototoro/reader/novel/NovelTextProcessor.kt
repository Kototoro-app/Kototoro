package org.skepsun.kototoro.reader.novel

import org.skepsun.kototoro.core.replace.ReplaceRule
import org.skepsun.kototoro.core.replace.ReplaceRuleSource
import org.skepsun.kototoro.core.replace.applyReplaceRules
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

data class NovelTextContext(
    val sourceName: String = "",
    val scopeName: String = "",
    val chapterId: Long = 0L,
    val chapterTitle: String = "",
    val replaceRulesEnabled: Boolean = true,
    val disabledReplaceRuleIds: Set<Long> = emptySet(),
)

data class ProcessedNovelText(
    val text: String,
    val revision: String,
    val failedRuleNames: List<String> = emptyList(),
)

data class NovelTextInput(
    val text: String,
    val context: NovelTextContext,
)

interface NovelTextProcessor {
    suspend fun process(text: String, context: NovelTextContext): ProcessedNovelText

    suspend fun processTitle(text: String, context: NovelTextContext): ProcessedNovelText = process(text, context)

    suspend fun processTitles(inputs: List<NovelTextInput>): List<ProcessedNovelText> = inputs.map { input ->
        processTitle(input.text, input.context)
    }
}

@Singleton
class DefaultNovelTextProcessor @Inject constructor(
    private val replaceRuleSource: ReplaceRuleSource,
) : NovelTextProcessor {

    override suspend fun process(text: String, context: NovelTextContext): ProcessedNovelText {
        if (!context.replaceRulesEnabled) return unchanged(text)
        val rules = applicableRules(
            rules = replaceRuleSource.getContentRules(
                scopeName = context.scopeName,
                origin = context.sourceName,
            ),
            target = ReplaceRule.Scope.CONTENT,
            context = context,
        )
        return processWithRules(text, rules)
    }

    override suspend fun processTitle(text: String, context: NovelTextContext): ProcessedNovelText {
        if (!context.replaceRulesEnabled) return unchanged(text)
        val rules = applicableRules(
            rules = replaceRuleSource.getTitleRules(
                scopeName = context.scopeName,
                origin = context.sourceName,
            ),
            target = ReplaceRule.Scope.TITLE,
            context = context,
        )
        return processWithRules(text, rules)
    }

    override suspend fun processTitles(inputs: List<NovelTextInput>): List<ProcessedNovelText> {
        if (inputs.isEmpty()) return emptyList()
        val results = arrayOfNulls<ProcessedNovelText>(inputs.size)
        inputs.withIndex()
            .groupBy { indexed ->
                val context = indexed.value.context
                Triple(
                    context.scopeName,
                    context.sourceName,
                    context.replaceRulesEnabled to context.disabledReplaceRuleIds,
                )
            }
            .values
            .forEach { group ->
                val context = group.first().value.context
                if (!context.replaceRulesEnabled) {
                    group.forEach { indexed ->
                        results[indexed.index] = unchanged(indexed.value.text)
                    }
                    return@forEach
                }
                val rules = applicableRules(
                    rules = replaceRuleSource.getTitleRules(
                        scopeName = context.scopeName,
                        origin = context.sourceName,
                    ),
                    target = ReplaceRule.Scope.TITLE,
                    context = context,
                )
                group.forEach { indexed ->
                    results[indexed.index] = processWithRules(
                        text = indexed.value.text,
                        rules = rules,
                    )
                }
            }
        return results.map { requireNotNull(it) }
    }

    private fun unchanged(text: String): ProcessedNovelText = ProcessedNovelText(
        text = text,
        revision = revisionOf(text, emptyList()),
    )

    private fun applicableRules(
        rules: List<ReplaceRule>,
        target: ReplaceRule.Scope,
        context: NovelTextContext,
    ): List<ReplaceRule> = rules.filter { rule ->
        rule.id !in context.disabledReplaceRuleIds &&
        rule.appliesTo(
            target = target,
            scopeValues = listOf(context.scopeName, context.sourceName),
        )
    }

    private suspend fun processWithRules(
        text: String,
        rules: List<ReplaceRule>,
    ): ProcessedNovelText {
        // The per-rule timeout/skip loop lives in `core-domain` so the Windows reader fails rules the same way.
        val applied = applyReplaceRules(text = text, rules = rules)
        return ProcessedNovelText(
            text = applied.text,
            revision = revisionOf(applied.text, rules),
            failedRuleNames = applied.failedRuleNames,
        )
    }

    private fun revisionOf(text: String, rules: List<ReplaceRule>): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(text.toByteArray(Charsets.UTF_8))
        rules.forEach { rule ->
            digest.update(0)
            digest.update(rule.id.toString().toByteArray(Charsets.UTF_8))
            digest.update(rule.order.toString().toByteArray(Charsets.UTF_8))
        }
        return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
    }
}
