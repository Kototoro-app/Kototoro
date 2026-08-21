package org.skepsun.kototoro.core.parser.rule

import com.jayway.jsonpath.JsonPath
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import us.codecraft.xsoup.Xsoup
import java.net.URI
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Default implementation of the RuleEngine interface
 * 
 * Supports CSS selectors and regular expressions for parsing HTML content.
 * Rules are compiled and cached for efficient reuse.
 * 
 * Performance optimizations:
 * - Increased cache size to 500 rules
 * - Cache prewarming for common rules
 * - Cache hit rate monitoring
 * 
 * @param cache Optional rule cache for performance optimization
 */
@Singleton
class DefaultRuleEngine @Inject constructor(
    private val cache: RuleCache,
) : RuleEngine {
    
    companion object {
        /**
         * Common rules that are frequently used across different sources
         * These rules are prewarmed in the cache for better performance
         */
        private val COMMON_RULES = listOf(
            "a@href",
            "img@src",
            "div@text",
            "span@text",
            "a@text",
            "h1@text",
            "h2@text",
            "h3@text",
            "p@text",
            "div.title@text",
            "div.content@text",
            "div.author@text",
            "div.description@text",
            "img@alt",
            "a@title",
        )
    }
    
    init {
        // Prewarm cache with common rules
        prewarmCache()
    }
    
    /**
     * Prewarm the cache with commonly used rules
     * This improves performance for the first use of these rules
     */
    private fun prewarmCache() {
        cache.prewarm(COMMON_RULES) { rule -> compileRule(rule) }
    }
    
    /**
     * Get cache statistics for monitoring
     */
    fun getCacheStats(): CacheStats = cache.getStats()
    
    /**
     * Log cache statistics
     */
    fun logCacheStats() = cache.logStats()
    
    override fun parseField(element: Element, rule: String): String {
        if (rule.isBlank()) return ""
        
        return try {
            val compiled = cache.getOrCompile(rule) { compileRule(it) }
            executeRule(element, compiled)
        } catch (e: Exception) {
            // Log error but don't crash - return empty string
            org.skepsun.kototoro.core.jsonsource.JsonSourceLogger.logRuleSyntaxError(rule, e.message ?: "Unknown error")
            ""
        }
    }
    
    override fun parseList(
        document: Document,
        listRule: String,
        itemRules: Map<String, String>,
    ): List<Map<String, String>> {
        if (listRule.isBlank()) return emptyList()
        
        return try {
            // Check if this is a Legado chained selector (contains @ with tag/class/id)
            // If so, we need to compile and execute it as a rule, not just convert the prefix
            val items = if (listRule.contains("@tag.") || 
                           listRule.contains("@class.") || 
                           listRule.contains("@id.") ||
                           listRule.matches(Regex(".*\\.[0-9]+@.*"))) {
                // This is a Legado chained selector, compile and execute it
                android.util.Log.d("RuleEngine", "Detected Legado chained selector: $listRule")
                val compiled = compileRule(listRule)
                
                // Execute the rule chain to get the target elements
                // We need to handle this specially for list selection
                executeLegadoChainForList(document, compiled)
            } else {
                // Simple selector, just convert and select
                val jsoupSelector = convertLegadoSelectorToJsoup(listRule)
                android.util.Log.d("RuleEngine", "Simple selector: $listRule -> $jsoupSelector")
                val selected = document.select(jsoupSelector)
                android.util.Log.d("RuleEngine", "Found ${selected.size} items with selector: $jsoupSelector")
                selected
            }
            
            if (items.isEmpty()) {
                org.skepsun.kototoro.core.jsonsource.JsonSourceLogger.logWarning(
                    "No items found with selector '$listRule'"
                )
            }
            
            items.mapNotNull { item ->
                val result = mutableMapOf<String, String>()
                var hasData = false
                
                for ((key, rule) in itemRules) {
                    if (rule.isBlank()) {
                        continue
                    }
                    
                    val value = parseField(item, rule)
                    
                    if (value.isNotEmpty()) {
                        result[key] = value
                        hasData = true
                    }
                }
                
                if (hasData) result else null
            }
        } catch (e: Exception) {
            // Log error but don't crash - return empty list
            org.skepsun.kototoro.core.jsonsource.JsonSourceLogger.logError("Error parsing list with rule '$listRule'", e)
            emptyList()
        }
    }
    
    /**
     * Execute a Legado chained selector for list selection
     * This handles selectors like "id.book-img-text.0@tag.li"
     */
    private fun executeLegadoChainForList(document: Document, rule: CompiledRule): org.jsoup.select.Elements {
        // If it's a chained rule, execute each step
        if (rule.chainedRules != null && rule.chainedRules.isNotEmpty()) {
            var currentElements = org.jsoup.select.Elements(document)
            
            for (chainedRule in rule.chainedRules) {
                val nextElements = org.jsoup.select.Elements()
                
                for (element in currentElements) {
                    val jsoupSelector = convertLegadoSelectorToJsoup(chainedRule.selector)
                    val selected = element.select(jsoupSelector)
                    
                    // Apply index if specified
                    if (chainedRule.elementIndex != null) {
                        selected.getOrNull(chainedRule.elementIndex)?.let { nextElements.add(it) }
                    } else {
                        nextElements.addAll(selected)
                    }
                }
                
                currentElements = nextElements
            }
            
            return currentElements
        }
        
        // Simple rule, just select from document
        val jsoupSelector = convertLegadoSelectorToJsoup(rule.selector)
        return document.select(jsoupSelector)
    }
    
    /**
     * Convert Legado-style selector to Jsoup selector
     * Legado format: "class.item" -> Jsoup format: ".item"
     * Legado format: "id.content" -> Jsoup format: "#content"
     * Legado format: "tag.div" -> Jsoup format: "div"
     */
    private fun convertLegadoSelectorToJsoup(selector: String): String {
        return when {
            selector.startsWith("@css:") -> selector.removePrefix("@css:")
            selector.startsWith("class.") -> ".${selector.removePrefix("class.")}"
            selector.startsWith("id.") -> "#${selector.removePrefix("id.")}"
            selector.startsWith("tag.") -> selector.removePrefix("tag.")
            else -> selector
        }
    }
    
    override fun compileRule(rule: String): CompiledRule {
        if (rule.isBlank()) {
            throw IllegalArgumentException("Rule cannot be blank")
        }
        
        // Check for || operator (OR - alternative rules)
        // Must check before ## to avoid conflicts
        if (rule.contains("||") && !isInsideQuotes(rule, "||")) {
            return compileRuleWithOr(rule)
        }
        
        // Check for && operator (AND - combined rules)
        // Must check before ## to avoid conflicts
        if (rule.contains("&&") && !isInsideQuotes(rule, "&&")) {
            return compileRuleWithAnd(rule)
        }
        
        // Check if it's a rule chain (contains ##)
        if (rule.contains("##")) {
            return compileRuleChain(rule)
        }
        
        // Check if it's a regex rule
        if (rule.startsWith("@regex:")) {
            return compileRegexRule(rule)
        }
        
        // Check if it's an XPath rule
        if (rule.startsWith("@xpath:") || rule.startsWith("//") || rule.startsWith("/")) {
            return compileXPathRule(rule)
        }
        
        // Check if it's a JSONPath rule
        if (rule.startsWith("@jsonpath:") || rule.startsWith("$.")) {
            return compileJsonPathRule(rule)
        }
        
        // Otherwise, treat as CSS selector
        return compileCssRule(rule)
    }
    
    /**
     * Check if a pattern is inside quotes
     * This prevents splitting on operators that are part of string literals
     */
    private fun isInsideQuotes(rule: String, pattern: String): Boolean {
        val index = rule.indexOf(pattern)
        if (index == -1) return false
        
        var inSingleQuote = false
        var inDoubleQuote = false
        var escaped = false
        
        for (i in 0 until index) {
            val c = rule[i]
            
            if (escaped) {
                escaped = false
                continue
            }
            
            when (c) {
                '\\' -> escaped = true
                '\'' -> if (!inDoubleQuote) inSingleQuote = !inSingleQuote
                '"' -> if (!inSingleQuote) inDoubleQuote = !inDoubleQuote
            }
        }
        
        return inSingleQuote || inDoubleQuote
    }
    
    /**
     * Execute a compiled rule on an element
     */
    private fun executeRule(element: Element, rule: CompiledRule): String {
        // If it has alternative rules (|| operator), try each until one succeeds
        if (rule.alternativeRules != null) {
            return executeRuleWithOr(element, rule)
        }
        
        // If it has combined rules (&& operator), execute all and merge
        if (rule.combinedRules != null) {
            return executeRuleWithAnd(element, rule)
        }
        
        // If it's a rule chain, execute each rule in sequence
        if (rule.chainedRules != null) {
            return executeRuleChain(element, rule)
        }
        
        val result = when (rule.type) {
            RuleType.CSS -> executeCssRule(element, rule)
            RuleType.REGEX -> executeRegexRule(element, rule)
            RuleType.XPATH -> executeXPathRule(element, rule)
            RuleType.JSON_PATH -> executeJsonPathRule(element, rule)
        }
        
        // Apply regex replacement if specified (Legado ## format)
        val afterReplacement = if (rule.regexReplacement != null) {
            result.replace(rule.regexReplacement, "")
        } else {
            result
        }
        
        // Apply modifiers to the result
        return applyModifiers(afterReplacement, rule.modifiers, element)
    }
    
    /**
     * Execute a rule with || operator (OR - alternative rules)
     * Try each rule in order until one returns a non-empty result
     */
    private fun executeRuleWithOr(element: Element, rule: CompiledRule): String {
        val alternatives = rule.alternativeRules ?: return ""
        
        for (altRule in alternatives) {
            val result = executeRule(element, altRule)
            if (result.isNotEmpty()) {
                return result
            }
        }
        
        return ""
    }
    
    /**
     * Execute a rule with && operator (AND - combined rules)
     * Execute all rules and combine their results with newline
     */
    private fun executeRuleWithAnd(element: Element, rule: CompiledRule): String {
        val combined = rule.combinedRules ?: return ""
        
        val results = combined.mapNotNull { combinedRule ->
            val result = executeRule(element, combinedRule)
            if (result.isNotEmpty()) result else null
        }
        
        return results.joinToString("\n")
    }
    
    /**
     * Compile a CSS selector rule
     * Format: "selector@attribute" or just "selector"
     * Examples: "div.title@text", "a@href", "img@src", "div.content"
     * Legado format: "tag.h3.0@tag.a.0@text" (chained selectors with indices)
     * Can also include modifiers: "a@href@absoluteURL"
     * Index expressions: "div[0]", "div[0:5]", "div[0,2,4]", "div[!0,1]"
     */
    private fun compileCssRule(rule: String): CompiledRule {
        // Check if this is a Legado-style chained selector BEFORE extracting modifiers
        // This is critical because extractModifiers would incorrectly treat @title, @href, etc.
        // as modifiers instead of attribute selectors
        // Format 1: "tag.a.0@tag.span@text" (contains @tag. or @class. or @id.)
        // Format 2: "tag.a.0@title" (starts with tag./class./id. and contains @)
        val isLegadoChained = rule.contains("@tag.") || 
                              rule.contains("@class.") || 
                              rule.contains("@id.") ||
                              (rule.contains("@") && 
                               (rule.startsWith("tag.") || 
                                rule.startsWith("class.") || 
                                rule.startsWith("id.")))
        
        if (isLegadoChained) {
            // This is a chained selector, compile as a chain
            // Don't extract modifiers for Legado chained selectors
            return compileLegadoChainedSelector(rule, emptyList())
        }
        
        // Extract modifiers for non-chained selectors
        val (ruleWithoutModifiers, modifiers) = extractModifiers(rule)
        
        val parts = ruleWithoutModifiers.split("@", limit = 2)
        val selectorWithIndex = parts[0].trim()
        val attribute = if (parts.size > 1) parts[1].trim() else null
        
        // Parse index expression if present
        val (selector, indexSpec) = parseIndexExpression(selectorWithIndex)
        
        return CompiledRule(
            type = RuleType.CSS,
            selector = selector,
            attribute = attribute,
            modifiers = modifiers,
            indexSpec = indexSpec,
        )
    }
    
    /**
     * Compile a Legado-style chained selector
     * Format: "tag.h3.0@tag.a.0@text"
     * This means: select h3 element at index 0, then select a element at index 0, then get text
     */
    private fun compileLegadoChainedSelector(rule: String, modifiers: List<RuleModifier>): CompiledRule {
        val parts = rule.split("@")
        val chainedRules = mutableListOf<CompiledRule>()
        
        for ((index, part) in parts.withIndex()) {
            val trimmed = part.trim()
            if (trimmed.isEmpty()) continue
            
            // Check if this is the final attribute (text, href, src, etc.)
            if (index == parts.size - 1 && !trimmed.contains(".")) {
                // This is an attribute, add it to the last rule
                if (chainedRules.isNotEmpty()) {
                    val lastRule = chainedRules.last()
                    chainedRules[chainedRules.size - 1] = lastRule.copy(attribute = trimmed)
                }
            } else {
                // This is a selector
                val (selector, elementIndex) = parseLegadoSelector(trimmed)
                chainedRules.add(
                    CompiledRule(
                        type = RuleType.CSS,
                        selector = selector,
                        elementIndex = elementIndex,
                    )
                )
            }
        }
        
        return CompiledRule(
            type = RuleType.CSS,
            selector = rule,
            chainedRules = chainedRules,
            modifiers = modifiers,
        )
    }
    
    /**
     * Parse a Legado selector to extract the selector and index
     * Format: "tag.h3.0" -> ("h3", 0)
     * Format: "class.item.2" -> (".item", 2)
     * Format: "id.content.0" -> ("#content", 0)
     */
    private fun parseLegadoSelector(selector: String): Pair<String, Int?> {
        val parts = selector.split(".")
        if (parts.size < 2) return Pair(selector, null)
        
        val type = parts[0]
        val name = parts[1]
        val index = parts.getOrNull(2)?.toIntOrNull()
        
        val jsoupSelector = when (type) {
            "tag" -> name
            "class" -> ".$name"
            "id" -> "#$name"
            else -> selector
        }
        
        return Pair(jsoupSelector, index)
    }
    
    /**
     * Compile a regex rule
     * Format: "@regex:pattern"
     * Example: "@regex:<title>(.*?)</title>"
     * Can also include modifiers: "@regex:<title>(.*?)</title>@replace:old:new"
     */
    private fun compileRegexRule(rule: String): CompiledRule {
        val withoutPrefix = rule.removePrefix("@regex:").trim()
        
        // Extract modifiers
        val (pattern, modifiers) = extractModifiers(withoutPrefix)
        
        // Validate regex using SecurityValidator
        val validation = org.skepsun.kototoro.core.jsonsource.SecurityValidator.validateRegex(pattern)
        if (!validation.isValid) {
            throw IllegalArgumentException("Invalid regex pattern: ${validation.errors.joinToString(", ")}")
        }
        
        val regex = try {
            Regex(pattern)
        } catch (e: Exception) {
            throw IllegalArgumentException("Invalid regex pattern: $pattern", e)
        }
        
        return CompiledRule(
            type = RuleType.REGEX,
            selector = pattern,
            regex = regex,
            modifiers = modifiers,
        )
    }
    
    /**
     * Execute a CSS selector rule
     */
    private fun executeCssRule(element: Element, rule: CompiledRule): String {
        return try {
            // Convert Legado-style selector to Jsoup selector
            val jsoupSelector = convertLegadoSelectorToJsoup(rule.selector)
            
            // Debug logging for CSS pseudo-class selectors
            if (rule.selector.contains(":nth-of-type") || rule.selector.contains(":first-of-type")) {
                android.util.Log.d("RuleEngine", "[DEBUG] CSS Pseudo-class selector detected:")
                android.util.Log.d("RuleEngine", "  Original: ${rule.selector}")
                android.util.Log.d("RuleEngine", "  Converted: $jsoupSelector")
                android.util.Log.d("RuleEngine", "  Attribute: ${rule.attribute}")
            }
            
            val selected = if (jsoupSelector.isEmpty()) {
                element
            } else {
                val elements = element.select(jsoupSelector)
                
                if (rule.selector.contains(":nth-of-type") || rule.selector.contains(":first-of-type")) {
                    android.util.Log.d("RuleEngine", "  Found ${elements.size} elements")
                    elements.forEachIndexed { i, el -> 
                        android.util.Log.d("RuleEngine", "    [$i]: ${el.tagName()} - ${el.text().take(50)}")
                    }
                }
                
                // Apply index specification if present
                val filteredElements = if (rule.indexSpec != null) {
                    applyIndexSpec(elements, rule.indexSpec)
                } else if (rule.elementIndex != null) {
                    // Legacy single index support
                    elements.getOrNull(rule.elementIndex)?.let { 
                        org.jsoup.select.Elements(it) 
                    } ?: org.jsoup.select.Elements()
                } else {
                    elements
                }
                
                // Get first element or return empty
                filteredElements.firstOrNull() ?: return ""
            }
            
            val result = when (rule.attribute) {
                null, "text" -> selected.text()
                "html" -> selected.html()
                "outerHtml" -> selected.outerHtml()
                "src" -> selected.attr("src")
                "href" -> selected.attr("href")
                "alt" -> selected.attr("alt")
                "title" -> selected.attr("title")
                "class" -> selected.attr("class")
                "id" -> selected.attr("id")
                "data" -> selected.data()
                "ownText" -> selected.ownText()
                else -> selected.attr(rule.attribute)
            }
            
            if (rule.selector.contains(":nth-of-type") || rule.selector.contains(":first-of-type")) {
                android.util.Log.d("RuleEngine", "  Result: '$result'")
            }
            
            result
        } catch (e: Exception) {
            // Handle invalid selector gracefully
            if (rule.selector.contains(":nth-of-type") || rule.selector.contains(":first-of-type")) {
                android.util.Log.e("RuleEngine", "[ERROR] CSS selector failed: ${e.message}", e)
            }
            org.skepsun.kototoro.core.jsonsource.JsonSourceLogger.logRuleSyntaxError(
                "CSS: ${rule.selector}",
                e.message ?: "Unknown error"
            )
            ""
        }
    }
    
    /**
     * Execute a regex rule
     */
    private fun executeRegexRule(element: Element, rule: CompiledRule): String {
        val regex = rule.regex ?: return ""
        val text = element.html()
        
        val match = regex.find(text) ?: return ""
        
        // If there are capture groups, return the first one
        // Otherwise return the entire match
        return if (match.groupValues.size > 1) {
            match.groupValues[1]
        } else {
            match.value
        }
    }
    
    /**
     * Compile an XPath rule
     * Format: "@xpath://div[@class='title']" or "//div[@class='title']"
     */
    private fun compileXPathRule(rule: String): CompiledRule {
        val xpath = rule.removePrefix("@xpath:").trim()
        
        // Extract modifiers if present
        val (selector, modifiers) = extractModifiers(xpath)
        
        return CompiledRule(
            type = RuleType.XPATH,
            selector = selector,
            modifiers = modifiers,
        )
    }
    
    /**
     * Execute an XPath rule
     */
    private fun executeXPathRule(element: Element, rule: CompiledRule): String {
        return try {
            val result = Xsoup.compile(rule.selector).evaluate(element)
            result?.get() ?: ""
        } catch (e: Exception) {
            org.skepsun.kototoro.core.jsonsource.JsonSourceLogger.logRuleSyntaxError(
                "XPath: ${rule.selector}",
                e.message ?: "Unknown error"
            )
            ""
        }
    }
    
    /**
     * Compile a JSONPath rule
     * Format: "@jsonpath:$.data.items" or "$.data.items"
     */
    private fun compileJsonPathRule(rule: String): CompiledRule {
        val jsonPath = rule.removePrefix("@jsonpath:").trim()
        
        // Extract modifiers if present
        val (selector, modifiers) = extractModifiers(jsonPath)
        
        return CompiledRule(
            type = RuleType.JSON_PATH,
            selector = selector,
            modifiers = modifiers,
        )
    }
    
    /**
     * Execute a JSONPath rule
     */
    private fun executeJsonPathRule(element: Element, rule: CompiledRule): String {
        return try {
            // Get the text content as JSON
            val jsonText = element.text()
            val result = JsonPath.read<Any>(jsonText, rule.selector)
            
            // Convert result to string
            when (result) {
                is String -> result
                is List<*> -> result.joinToString(", ")
                else -> result.toString()
            }
        } catch (e: Exception) {
            org.skepsun.kototoro.core.jsonsource.JsonSourceLogger.logRuleSyntaxError(
                "JSONPath: ${rule.selector}",
                e.message ?: "Unknown error"
            )
            ""
        }
    }
    
    /**
     * Compile a rule chain (rules separated by ##)
     * Format: "div.container##a@href##@regex:id=(\d+)"
     * Or regex replacement: "selector@attribute##regex_pattern"
     * 
     * In Legado format, ## can mean:
     * 1. Regex replacement: "selector@text##pattern" - replace pattern in result
     * 2. Rule chaining: "selector1##selector2" - apply selector2 to result of selector1
     */
    private fun compileRuleChain(rule: String): CompiledRule {
        val parts = rule.split("##", limit = 2).map { it.trim() }
        
        if (parts.isEmpty()) {
            throw IllegalArgumentException("Rule chain cannot be empty")
        }
        
        // If there are exactly 2 parts and the second part doesn't start with a selector prefix,
        // treat it as a regex replacement pattern
        if (parts.size == 2) {
            val firstPart = parts[0]
            val secondPart = parts[1]
            
            // Check if second part looks like a regex pattern (not a selector)
            val isRegexReplacement = !secondPart.startsWith("@") && 
                                     !secondPart.startsWith(".") && 
                                     !secondPart.startsWith("#") &&
                                     !secondPart.contains("@")
            
            if (isRegexReplacement) {
                // This is a regex replacement: selector@attribute##pattern
                // Compile the first part as a normal rule
                val baseRule = compileRule(firstPart)
                
                // Create a regex for replacement
                val regexPattern = try {
                    Regex(secondPart)
                } catch (e: Exception) {
                    // If it's not a valid regex, treat it as a literal string to remove
                    Regex(Regex.escape(secondPart))
                }
                
                // Return a rule with regex replacement modifier
                return baseRule.copy(
                    regexReplacement = regexPattern
                )
            }
        }
        
        // Otherwise, compile as a rule chain
        val chainedRules = parts.map { part ->
            // Recursively compile each part (without the ## separator)
            compileRule(part)
        }
        
        // Return a special rule that contains the chain
        return CompiledRule(
            type = RuleType.CSS, // Type doesn't matter for chains
            selector = rule,
            chainedRules = chainedRules,
        )
    }
    
    /**
     * Execute a rule chain
     */
    private fun executeRuleChain(element: Element, rule: CompiledRule): String {
        val chainedRules = rule.chainedRules ?: return ""
        
        var currentElement = element
        var currentResult = ""
        
        for ((index, chainedRule) in chainedRules.withIndex()) {
            if (index == 0) {
                // First rule: execute on the original element
                if (chainedRule.type == RuleType.CSS && chainedRule.selector.isNotEmpty()) {
                    val jsoupSelector = convertLegadoSelectorToJsoup(chainedRule.selector)
                    
                    // Handle element index if specified
                    currentElement = if (chainedRule.elementIndex != null) {
                        val elements = currentElement.select(jsoupSelector)
                        elements.getOrNull(chainedRule.elementIndex) ?: return ""
                    } else {
                        currentElement.selectFirst(jsoupSelector) ?: return ""
                    }
                    
                    // If there's an attribute, extract it
                    if (chainedRule.attribute != null) {
                        currentResult = when (chainedRule.attribute) {
                            "text" -> currentElement.text()
                            "html" -> currentElement.html()
                            "href" -> currentElement.attr("href")
                            "src" -> currentElement.attr("src")
                            else -> currentElement.attr(chainedRule.attribute)
                        }
                    }
                } else {
                    currentResult = executeRule(currentElement, chainedRule)
                }
            } else {
                // Subsequent rules: execute on the current element or result
                currentResult = when (chainedRule.type) {
                    RuleType.REGEX -> {
                        // For regex, apply to the current result string
                        val regex = chainedRule.regex ?: continue
                        val match = regex.find(currentResult) ?: ""
                        if (match is MatchResult) {
                            if (match.groupValues.size > 1) match.groupValues[1] else match.value
                        } else {
                            ""
                        }
                    }
                    RuleType.CSS -> {
                        // For CSS, select from current element
                        if (chainedRule.selector.isNotEmpty()) {
                            val jsoupSelector = convertLegadoSelectorToJsoup(chainedRule.selector)
                            
                            currentElement = if (chainedRule.elementIndex != null) {
                                val elements = currentElement.select(jsoupSelector)
                                elements.getOrNull(chainedRule.elementIndex) ?: return ""
                            } else {
                                currentElement.selectFirst(jsoupSelector) ?: return ""
                            }
                        }
                        
                        // Extract attribute if specified
                        if (chainedRule.attribute != null) {
                            when (chainedRule.attribute) {
                                "text" -> currentElement.text()
                                "html" -> currentElement.html()
                                "href" -> currentElement.attr("href")
                                "src" -> currentElement.attr("src")
                                else -> currentElement.attr(chainedRule.attribute)
                            }
                        } else {
                            currentElement.text()
                        }
                    }
                    else -> {
                        // For other types, execute on the current element
                        executeRule(currentElement, chainedRule)
                    }
                }
            }
        }
        
        return currentResult
    }
    
    /**
     * Extract modifiers from a rule string
     * Returns the selector without modifiers and the list of modifiers
     */
    private fun extractModifiers(rule: String): Pair<String, List<RuleModifier>> {
        val modifiers = mutableListOf<RuleModifier>()
        var selector = rule
        
        // Check for modifiers at the end of the rule
        val modifierPattern = Regex("""@(\w+)(?::([^@]+))?$""")
        var match = modifierPattern.find(selector)
        
        while (match != null) {
            val modifierName = match.groupValues[1]
            val modifierArg = match.groupValues.getOrNull(2)
            
            val modifier = when (modifierName) {
                "get" -> modifierArg?.toIntOrNull()?.let { RuleModifier.Get(it) }
                "first" -> RuleModifier.First
                "last" -> RuleModifier.Last
                "size" -> RuleModifier.Size
                "replace" -> {
                    val parts = modifierArg?.split(":", limit = 2)
                    if (parts?.size == 2) {
                        RuleModifier.Replace(parts[0], parts[1])
                    } else null
                }
                "substring" -> {
                    val parts = modifierArg?.split(":", limit = 2)
                    val start = parts?.getOrNull(0)?.toIntOrNull() ?: 0
                    val end = parts?.getOrNull(1)?.toIntOrNull()
                    RuleModifier.Substring(start, end)
                }
                "absoluteURL" -> RuleModifier.AbsoluteURL
                "relativeURL" -> RuleModifier.RelativeURL
                else -> null
            }
            
            if (modifier != null) {
                modifiers.add(0, modifier) // Add to beginning to maintain order
            }
            
            // Remove the modifier from the selector
            selector = selector.substring(0, match.range.first)
            match = modifierPattern.find(selector)
        }
        
        return Pair(selector.trim(), modifiers)
    }
    
    /**
     * Apply modifiers to a result string
     */
    private fun applyModifiers(
        result: String,
        modifiers: List<RuleModifier>,
        element: Element,
    ): String {
        var current = result
        
        for (modifier in modifiers) {
            current = when (modifier) {
                is RuleModifier.Get -> {
                    // Split by whitespace and get the specified index
                    val parts = current.split(Regex("\\s+"))
                    parts.getOrNull(modifier.index) ?: ""
                }
                is RuleModifier.First -> {
                    current.split(Regex("\\s+")).firstOrNull() ?: ""
                }
                is RuleModifier.Last -> {
                    current.split(Regex("\\s+")).lastOrNull() ?: ""
                }
                is RuleModifier.Size -> {
                    current.split(Regex("\\s+")).size.toString()
                }
                is RuleModifier.Replace -> {
                    current.replace(modifier.old, modifier.new)
                }
                is RuleModifier.Substring -> {
                    val start = modifier.start.coerceIn(0, current.length)
                    val end = modifier.end?.coerceIn(start, current.length) ?: current.length
                    current.substring(start, end)
                }
                is RuleModifier.AbsoluteURL -> {
                    // Convert relative URL to absolute
                    try {
                        val baseUri = element.baseUri()
                        if (baseUri.isNotEmpty()) {
                            URI(baseUri).resolve(current).toString()
                        } else {
                            current
                        }
                    } catch (e: Exception) {
                        current
                    }
                }
                is RuleModifier.RelativeURL -> {
                    // Extract relative path from absolute URL
                    try {
                        val uri = URI(current)
                        uri.path + (if (uri.query != null) "?${uri.query}" else "")
                    } catch (e: Exception) {
                        current
                    }
                }
            }
        }
        
        return current
    }
    
    /**
     * Compile a rule with || operator (OR - alternative rules)
     * Format: "rule1||rule2||rule3"
     * Tries each rule in order until one returns a non-empty result
     * 
     * Example: "img@src||img@data-src||img@data-original"
     */
    private fun compileRuleWithOr(rule: String): CompiledRule {
        val parts = splitByOperator(rule, "||")
        
        if (parts.size == 1) {
            return compileRule(parts[0])
        }
        
        // Compile each alternative rule
        val alternativeRules = parts.map { part ->
            compileRule(part.trim())
        }
        
        return CompiledRule(
            type = RuleType.CSS,
            selector = rule,
            alternativeRules = alternativeRules,
        )
    }
    
    /**
     * Compile a rule with && operator (AND - combined rules)
     * Format: "rule1&&rule2&&rule3"
     * Executes all rules and combines their results
     * 
     * Example: "div.title@text&&div.subtitle@text"
     */
    private fun compileRuleWithAnd(rule: String): CompiledRule {
        val parts = splitByOperator(rule, "&&")
        
        if (parts.size == 1) {
            return compileRule(parts[0])
        }
        
        // Compile each combined rule
        val combinedRules = parts.map { part ->
            compileRule(part.trim())
        }
        
        return CompiledRule(
            type = RuleType.CSS,
            selector = rule,
            combinedRules = combinedRules,
        )
    }
    
    /**
     * Split a rule by an operator, respecting quotes and brackets
     * This ensures we don't split on operators inside strings or selectors
     */
    private fun splitByOperator(rule: String, operator: String): List<String> {
        val parts = mutableListOf<String>()
        var currentPart = StringBuilder()
        var inSingleQuote = false
        var inDoubleQuote = false
        var inBracket = 0
        var inParen = 0
        var escaped = false
        
        var i = 0
        while (i < rule.length) {
            val c = rule[i]
            
            if (escaped) {
                currentPart.append(c)
                escaped = false
                i++
                continue
            }
            
            when (c) {
                '\\' -> {
                    escaped = true
                    currentPart.append(c)
                }
                '\'' -> {
                    if (!inDoubleQuote) inSingleQuote = !inSingleQuote
                    currentPart.append(c)
                }
                '"' -> {
                    if (!inSingleQuote) inDoubleQuote = !inDoubleQuote
                    currentPart.append(c)
                }
                '[' -> {
                    if (!inSingleQuote && !inDoubleQuote) inBracket++
                    currentPart.append(c)
                }
                ']' -> {
                    if (!inSingleQuote && !inDoubleQuote) inBracket--
                    currentPart.append(c)
                }
                '(' -> {
                    if (!inSingleQuote && !inDoubleQuote) inParen++
                    currentPart.append(c)
                }
                ')' -> {
                    if (!inSingleQuote && !inDoubleQuote) inParen--
                    currentPart.append(c)
                }
                else -> {
                    // Check if we're at the operator
                    if (!inSingleQuote && !inDoubleQuote && inBracket == 0 && inParen == 0) {
                        if (rule.substring(i).startsWith(operator)) {
                            // Found the operator, split here
                            parts.add(currentPart.toString())
                            currentPart = StringBuilder()
                            i += operator.length
                            continue
                        }
                    }
                    currentPart.append(c)
                }
            }
            
            i++
        }
        
        // Add the last part
        if (currentPart.isNotEmpty()) {
            parts.add(currentPart.toString())
        }
        
        return parts
    }

}

    /**
     * Parse index expression from selector
     * Supports:
     * - Single index: [0], [-1]
     * - Multiple indexes: [0,2,4]
     * - Range: [0:5], [0:10:2]
     * - Exclude: [!0,1]
     * 
     * Returns the selector without index and the parsed IndexSpec
     */
    private fun parseIndexExpression(selector: String): Pair<String, IndexSpec?> {
        // Check for [...]  pattern
        val indexPattern = Regex("""^(.+?)\[([^\]]+)\]$""")
        val match = indexPattern.find(selector) ?: return Pair(selector, null)
        
        val baseSelector = match.groupValues[1]
        val indexExpr = match.groupValues[2]
        
        // Check for exclude pattern [!...]
        if (indexExpr.startsWith("!")) {
            val indexes = indexExpr.substring(1).split(",")
                .mapNotNull { it.trim().toIntOrNull() }
            return Pair(baseSelector, IndexSpec.Exclude(indexes))
        }
        
        // Check for range pattern [start:end] or [start:end:step]
        if (indexExpr.contains(":")) {
            val parts = indexExpr.split(":")
            val start = parts.getOrNull(0)?.trim()?.toIntOrNull() ?: 0
            val end = parts.getOrNull(1)?.trim()?.toIntOrNull() ?: -1
            val step = parts.getOrNull(2)?.trim()?.toIntOrNull() ?: 1
            return Pair(baseSelector, IndexSpec.Range(start, end, step))
        }
        
        // Check for multiple indexes [0,2,4]
        if (indexExpr.contains(",")) {
            val indexes = indexExpr.split(",")
                .mapNotNull { it.trim().toIntOrNull() }
            return Pair(baseSelector, IndexSpec.Multiple(indexes))
        }
        
        // Single index [0]
        val index = indexExpr.trim().toIntOrNull()
        return if (index != null) {
            Pair(baseSelector, IndexSpec.Single(index))
        } else {
            Pair(selector, null)
        }
    }
    
    /**
     * Apply index specification to select elements
     */
    private fun applyIndexSpec(elements: org.jsoup.select.Elements, indexSpec: IndexSpec?): org.jsoup.select.Elements {
        if (indexSpec == null) return elements
        
        val size = elements.size
        if (size == 0) return elements
        
        return when (indexSpec) {
            is IndexSpec.Single -> {
                val index = if (indexSpec.index < 0) {
                    size + indexSpec.index
                } else {
                    indexSpec.index
                }
                
                if (index in 0 until size) {
                    org.jsoup.select.Elements(elements[index])
                } else {
                    org.jsoup.select.Elements()
                }
            }
            
            is IndexSpec.Multiple -> {
                val result = org.jsoup.select.Elements()
                for (idx in indexSpec.indexes) {
                    val index = if (idx < 0) size + idx else idx
                    if (index in 0 until size) {
                        result.add(elements[index])
                    }
                }
                result
            }
            
            is IndexSpec.Range -> {
                val start = if (indexSpec.start < 0) {
                    (size + indexSpec.start).coerceAtLeast(0)
                } else {
                    indexSpec.start.coerceAtMost(size - 1)
                }
                
                val end = if (indexSpec.end < 0) {
                    (size + indexSpec.end).coerceAtLeast(0)
                } else if (indexSpec.end >= size) {
                    size - 1
                } else {
                    indexSpec.end
                }
                
                val result = org.jsoup.select.Elements()
                if (start <= end) {
                    var i = start
                    while (i <= end) {
                        if (i in 0 until size) {
                            result.add(elements[i])
                        }
                        i += indexSpec.step
                    }
                } else {
                    // Reverse range
                    var i = start
                    while (i >= end) {
                        if (i in 0 until size) {
                            result.add(elements[i])
                        }
                        i -= indexSpec.step
                    }
                }
                result
            }
            
            is IndexSpec.Exclude -> {
                val excludeSet = indexSpec.indexes.map { idx ->
                    if (idx < 0) size + idx else idx
                }.toSet()
                
                val result = org.jsoup.select.Elements()
                for (i in 0 until size) {
                    if (i !in excludeSet) {
                        result.add(elements[i])
                    }
                }
                result
            }
        }
    }
