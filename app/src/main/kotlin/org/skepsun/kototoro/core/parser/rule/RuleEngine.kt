package org.skepsun.kototoro.core.parser.rule

import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/**
 * Rule engine interface for parsing HTML content using various rule types
 * 
 * The rule engine interprets and executes selector rules defined in JSON configurations
 * (such as Legado book sources) to extract data from HTML documents.
 */
interface RuleEngine {
    /**
     * Parse a single field from an HTML element using a rule
     * 
     * @param element The HTML element to parse
     * @param rule The rule string (e.g., "div.title@text", "@regex:<title>(.*?)</title>")
     * @return The extracted text, or empty string if not found
     */
    fun parseField(element: Element, rule: String): String
    
    /**
     * Parse a list of items from an HTML document
     * 
     * @param document The HTML document to parse
     * @param listRule The rule to select list items (e.g., "div.book-item")
     * @param itemRules Map of field names to rules for extracting data from each item
     * @return List of maps containing extracted data for each item
     */
    fun parseList(
        document: Document,
        listRule: String,
        itemRules: Map<String, String>,
    ): List<Map<String, String>>
    
    /**
     * Compile a rule string into a CompiledRule for efficient reuse
     * 
     * @param rule The rule string to compile
     * @return The compiled rule object
     * @throws IllegalArgumentException if the rule syntax is invalid
     */
    fun compileRule(rule: String): CompiledRule
}
