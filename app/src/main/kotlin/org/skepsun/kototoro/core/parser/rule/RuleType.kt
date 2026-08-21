package org.skepsun.kototoro.core.parser.rule

/**
 * Types of rules supported by the rule engine
 */
enum class RuleType {
    /**
     * CSS selector rule (e.g., "div.title", "a@href")
     */
    CSS,
    
    /**
     * Regular expression rule (e.g., "@regex:<title>(.*?)</title>")
     */
    REGEX,
    
    /**
     * XPath rule (future support)
     */
    XPATH,
    
    /**
     * JSONPath rule (future support)
     */
    JSON_PATH,
}
