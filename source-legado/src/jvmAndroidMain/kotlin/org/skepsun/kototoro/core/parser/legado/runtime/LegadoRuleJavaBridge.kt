package org.skepsun.kototoro.core.parser.legado.runtime

/** Methods exposed as `java` while a Legado rule is evaluated. */
interface LegadoRuleJavaBridge {
    fun ajax(url: Any): String?
    fun get(key: String): String
    fun put(key: String, value: String): String
    fun reGetBook()
    fun refreshTocUrl()
    fun getSource(): Any?
    fun getTag(): String?
}
