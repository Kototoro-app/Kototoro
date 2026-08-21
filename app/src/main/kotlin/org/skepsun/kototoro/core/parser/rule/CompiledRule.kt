package org.skepsun.kototoro.core.parser.rule

/**
 * Represents a compiled rule that can be efficiently executed
 * 
 * @property type The type of rule (CSS, REGEX, XPATH, JSON_PATH)
 * @property selector The main selector/pattern string
 * @property attribute Optional attribute to extract (for CSS rules like @text, @href)
 * @property regex Optional compiled regex pattern (for REGEX rules)
 * @property modifiers Optional list of modifiers to apply to the result
 * @property chainedRules Optional list of chained rules (for rule chains with ##)
 * @property elementIndex Optional index for selecting a specific element from multiple matches (Legado format)
 * @property regexReplacement Optional regex pattern for replacing/removing text from result (Legado ## format)
 * @property alternativeRules Optional list of alternative rules (for || operator - try each until one succeeds)
 * @property combinedRules Optional list of rules to combine (for && operator - execute all and merge results)
 */
data class CompiledRule(
    val type: RuleType,
    val selector: String,
    val attribute: String? = null,
    val regex: Regex? = null,
    val modifiers: List<RuleModifier> = emptyList(),
    val chainedRules: List<CompiledRule>? = null,
    val elementIndex: Int? = null,
    val indexSpec: IndexSpec? = null,
    val regexReplacement: Regex? = null,
    val alternativeRules: List<CompiledRule>? = null,
    val combinedRules: List<CompiledRule>? = null,
)

/**
 * Represents an index specification for element selection
 * Supports single index, multiple indexes, or range
 */
sealed class IndexSpec {
    /** Single index: div[0] or div.-1 */
    data class Single(val index: Int) : IndexSpec()
    
    /** Multiple indexes: div[0,2,4] */
    data class Multiple(val indexes: List<Int>) : IndexSpec()
    
    /** Range with optional step: div[0:5] or div[0:10:2] */
    data class Range(val start: Int, val end: Int, val step: Int = 1) : IndexSpec()
    
    /** Exclude indexes: div[!0,1] */
    data class Exclude(val indexes: List<Int>) : IndexSpec()
}

/**
 * Represents a modifier that can be applied to rule results
 */
sealed class RuleModifier {
    data class Get(val index: Int) : RuleModifier()
    object First : RuleModifier()
    object Last : RuleModifier()
    object Size : RuleModifier()
    data class Replace(val old: String, val new: String) : RuleModifier()
    data class Substring(val start: Int, val end: Int?) : RuleModifier()
    object AbsoluteURL : RuleModifier()
    object RelativeURL : RuleModifier()
}
