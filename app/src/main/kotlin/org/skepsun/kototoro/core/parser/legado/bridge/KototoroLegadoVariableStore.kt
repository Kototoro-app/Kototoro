package org.skepsun.kototoro.core.parser.legado.bridge

import android.content.SharedPreferences
import org.json.JSONObject
import org.skepsun.kototoro.core.parser.legado.runtime.LegadoVariableStore

/**
 * 基于 SharedPreferences 的 Legado 变量存储实现。
 *
 * 保持与当前 Kototoro/Legado 兼容路径一致：
 * - `sourceVariable_{sourceKey}` 存放整包 source 变量 JSON
 * - `userInfo_{sourceKey}` 存放登录信息 JSON
 * - `loginHeader_{sourceKey}` 存放登录头 JSON
 * - `v_{sourceKey}_{key}` 存放按键持久化变量
 */
class KototoroLegadoVariableStore(
    private val prefs: SharedPreferences,
) : LegadoVariableStore {

    override fun getSourceVariable(sourceKey: String): String? {
        return prefs.getString(sourceVariableKey(sourceKey), null)
    }

    override fun putSourceVariable(sourceKey: String, value: String?) {
        prefs.edit().apply {
            if (value == null) {
                remove(sourceVariableKey(sourceKey))
            } else {
                putString(sourceVariableKey(sourceKey), value)
            }
        }.apply()
    }

    override fun getLoginInfo(sourceKey: String): String? {
        return prefs.getString(loginInfoKey(sourceKey), null)
    }

    override fun putLoginInfo(sourceKey: String, value: String?) {
        prefs.edit().apply {
            if (value == null) {
                remove(loginInfoKey(sourceKey))
            } else {
                putString(loginInfoKey(sourceKey), value)
            }
        }.apply()
    }

    override fun getLoginHeader(sourceKey: String): String? {
        return prefs.getString(loginHeaderKey(sourceKey), null)
    }

    override fun putLoginHeader(sourceKey: String, value: String?) {
        prefs.edit().apply {
            if (value == null) {
                remove(loginHeaderKey(sourceKey))
            } else {
                putString(loginHeaderKey(sourceKey), value)
            }
        }.apply()
    }

    override fun getVariable(sourceKey: String, key: String): String? {
        return prefs.getString(variableKey(sourceKey, key), null)
    }

    fun putVariable(sourceKey: String, key: String, value: String?) {
        prefs.edit().apply {
            if (value == null) {
                remove(variableKey(sourceKey, key))
            } else {
                putString(variableKey(sourceKey, key), value)
            }
        }.apply()
        syncSourceVariableBlob(sourceKey, key, value)
    }

    private fun syncSourceVariableBlob(sourceKey: String, key: String, value: String?) {
        val current = prefs.getString(sourceVariableKey(sourceKey), null)
        val json = runCatching { JSONObject(current ?: "{}") }.getOrDefault(JSONObject())
        if (value == null) {
            json.remove(key)
        } else {
            json.put(key, value)
        }
        val serialized = if (json.length() == 0) null else json.toString()
        putSourceVariable(sourceKey, serialized)
    }

    private fun sourceVariableKey(sourceKey: String): String = "sourceVariable_$sourceKey"

    private fun loginInfoKey(sourceKey: String): String = "userInfo_$sourceKey"

    private fun loginHeaderKey(sourceKey: String): String = "loginHeader_$sourceKey"

    private fun variableKey(sourceKey: String, key: String): String = "v_${sourceKey}_$key"
}
