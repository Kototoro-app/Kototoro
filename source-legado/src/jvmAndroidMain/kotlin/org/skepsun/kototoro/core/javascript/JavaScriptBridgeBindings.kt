package org.skepsun.kototoro.core.javascript

/**
 * 允许临时 java bridge 额外向 JS 作用域注入变量。
 */
interface JavaScriptBridgeBindings {
    fun getBridgeBindings(): Map<String, Any?>
}
