package org.skepsun.kototoro.core.parser.legado

/**
 * Interface for storing and retrieving rule execution data.
 * Provides variable storage for cross-rule data sharing during parsing.
 *
 * Based on legado-with-MD3 RuleDataInterface pattern.
 */
interface RuleDataInterface {

    /**
     * Get stored variable value
     * @param key Variable key
     * @return Variable value or null if not found
     */
    fun getVariable(key: String): String?

    /**
     * Store variable value
     * @param key Variable key
     * @param value Variable value
     * @return Previous value or null
     */
    fun putVariable(key: String, value: String?): String?

    /**
     * Get all stored variables
     */
    fun getVariableMap(): Map<String, String>

    /**
     * Clear all variables
     */
    fun clearVariables()
}

/**
 * Default implementation of RuleDataInterface.
 * Thread-safe variable storage for rule execution.
 */
class RuleData : RuleDataInterface {

    @Volatile
    private var variableMap: MutableMap<String, String> = mutableMapOf()

    override fun getVariable(key: String): String? {
        return variableMap[key]
    }

    override fun putVariable(key: String, value: String?): String? {
        return if (value == null) {
            variableMap.remove(key)
        } else {
            variableMap.put(key, value)
        }
    }

    override fun getVariableMap(): Map<String, String> {
        return variableMap.toMap()
    }

    override fun clearVariables() {
        variableMap.clear()
    }

    /**
     * Create a copy with the same variables
     */
    fun copy(): RuleData {
        return RuleData().also {
            it.variableMap.putAll(this.variableMap)
        }
    }
}
