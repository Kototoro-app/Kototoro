package org.skepsun.kototoro.core.parser.legado

import java.util.regex.Pattern

/**
 * 负责把 SourceRule 中的参数化片段解析为结构化 segment。
 */
object LegadoSourceRuleSegmentParser {

    private val evalPattern = Pattern.compile("@get:\\{[^}]+?\\}|\\{\\{[\\w\\W]*?\\}\\}", Pattern.CASE_INSENSITIVE)
    private val regexPattern = Pattern.compile("\\$\\d{1,2}")

    data class ParseResult(
        val segments: List<Segment>,
        val mode: AnalyzeRule.Mode,
    )

    sealed interface Segment {
        data class Literal(val value: String) : Segment

        data class RegexGroup(val groupIndex: Int, val token: String) : Segment

        data class GetVariable(val key: String) : Segment

        data class JavaScript(val expression: String) : Segment
    }

    fun parse(
        rule: String,
        mode: AnalyzeRule.Mode,
    ): ParseResult {
        val segments = ArrayList<Segment>()
        val originalMode = mode
        var resolvedMode = mode
        var start = 0
        val evalMatcher = evalPattern.matcher(rule)

        if (evalMatcher.find()) {
            val prefix = rule.substring(start, evalMatcher.start())
            if (originalMode != AnalyzeRule.Mode.Js &&
                originalMode != AnalyzeRule.Mode.Regex &&
                (evalMatcher.start() == 0 || !prefix.contains("##"))
            ) {
                resolvedMode = AnalyzeRule.Mode.Regex
            }
            do {
                if (evalMatcher.start() > start) {
                    val literal = rule.substring(start, evalMatcher.start())
                    resolvedMode = splitRegexSegments(literal, originalMode, resolvedMode, segments)
                }
                val token = evalMatcher.group()
                when {
                    token.startsWith("@get:", true) -> {
                        segments += Segment.GetVariable(token.substring(6, token.lastIndex))
                    }

                    token.startsWith("{{") -> {
                        segments += Segment.JavaScript(token.substring(2, token.length - 2))
                    }

                    else -> {
                        resolvedMode = splitRegexSegments(token, originalMode, resolvedMode, segments)
                    }
                }
                start = evalMatcher.end()
            } while (evalMatcher.find())
        }

        if (rule.length > start) {
            val tail = rule.substring(start)
            resolvedMode = splitRegexSegments(tail, originalMode, resolvedMode, segments)
        }

        return ParseResult(segments, resolvedMode)
    }

    private fun splitRegexSegments(
        rule: String,
        originalMode: AnalyzeRule.Mode,
        mode: AnalyzeRule.Mode,
        segments: MutableList<Segment>,
    ): AnalyzeRule.Mode {
        var resolvedMode = mode
        var start = 0
        val ruleHead = rule.split("##")[0]
        val regexMatcher = regexPattern.matcher(ruleHead)

        if (regexMatcher.find()) {
            if (originalMode != AnalyzeRule.Mode.Js && originalMode != AnalyzeRule.Mode.Regex) {
                resolvedMode = AnalyzeRule.Mode.Regex
            }
            do {
                if (regexMatcher.start() > start) {
                    segments += Segment.Literal(rule.substring(start, regexMatcher.start()))
                }
                val token = regexMatcher.group()
                segments += Segment.RegexGroup(
                    groupIndex = token.substring(1).toInt(),
                    token = token,
                )
                start = regexMatcher.end()
            } while (regexMatcher.find())
        }
        if (rule.length > start) {
            segments += Segment.Literal(rule.substring(start))
        }
        return resolvedMode
    }
}
