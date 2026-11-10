package org.skepsun.kototoro.desktop.app

import org.skepsun.kototoro.core.javascript.LegadoScriptStore
import org.skepsun.kototoro.core.parser.legado.runtime.LegadoVariableStore

/** Uses the same source and login keys as Android's Legado preference store, which scripts read through `source`. */
internal class DesktopLegadoVariableStore(private val store: LegadoScriptStore) : LegadoVariableStore {
    override fun getVariable(sourceKey: String, key: String): String? = store.getString("v_${sourceKey}_$key")
    override fun getSourceVariable(sourceKey: String): String? = store.getString("sourceVariable_$sourceKey")
    override fun putSourceVariable(sourceKey: String, value: String?) = store.putString("sourceVariable_$sourceKey", value)
    override fun getLoginInfo(sourceKey: String): String? = store.getString("userInfo_$sourceKey")
    override fun putLoginInfo(sourceKey: String, value: String?) = store.putString("userInfo_$sourceKey", value)
    override fun getLoginHeader(sourceKey: String): String? = store.getString("loginHeader_$sourceKey")
    override fun putLoginHeader(sourceKey: String, value: String?) = store.putString("loginHeader_$sourceKey", value)
}
