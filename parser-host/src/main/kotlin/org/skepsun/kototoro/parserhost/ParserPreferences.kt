package org.skepsun.kototoro.parserhost

import org.skepsun.kototoro.core.source.SourceInvalidArgumentException
import org.skepsun.kototoro.core.source.SourcePreferenceChoice
import org.skepsun.kototoro.core.source.SourcePreferenceKind
import org.skepsun.kototoro.core.source.SourcePreferenceNode
import org.skepsun.kototoro.core.source.SourcePreferenceScreen
import org.skepsun.kototoro.core.source.SourcePreferenceUpdate
import org.skepsun.kototoro.core.source.SourcePreferenceUpdateStatus
import org.skepsun.kototoro.core.source.SourcePreferenceValue
import org.skepsun.kototoro.core.source.SourceRef
import org.skepsun.kototoro.parsers.config.ConfigKey
import java.util.UUID

/**
 * Settings screen of one parser source, derived from the keys it declares in `onCreateConfig`.
 * A revision guards against a stale screen writing over a newer one.
 */
internal class ParserPreferences(
    private val source: SourceRef,
    keys: List<ConfigKey<*>>,
    private val config: ParserSourceConfig,
) {
    private val keys = keys.distinctBy { it.key }.associateBy { nodeId(it) }
    private var revision = UUID.randomUUID().toString()

    fun screen(): SourcePreferenceScreen =
        SourcePreferenceScreen(source, revision, keys.map { (id, key) -> node(id, key) })

    fun update(expectedRevision: String, nodeId: String, value: SourcePreferenceValue): SourcePreferenceUpdate {
        if (expectedRevision != revision) throw SourceInvalidArgumentException()
        val key = keys[nodeId] ?: throw SourceInvalidArgumentException()
        val node = node(nodeId, key)
        val accepted = when (node.kind) {
            SourcePreferenceKind.TEXT -> (value as? SourcePreferenceValue.Text)?.value?.takeIf {
                key !is ConfigKey.Domain || ParserSourceConfig.isValidDomain(it.trim())
            }?.let { SourcePreferenceValue.Text(if (key is ConfigKey.Domain) it.trim() else it) }
            SourcePreferenceKind.TOGGLE -> value as? SourcePreferenceValue.Toggle
            // Domain presets are suggestions: any valid host (a private mirror) may be chosen as well.
            SourcePreferenceKind.CHOICE -> (value as? SourcePreferenceValue.Text)?.takeIf { selected ->
                node.choices.any { it.value == selected.value } ||
                    key is ConfigKey.Domain && ParserSourceConfig.isValidDomain(selected.value.trim())
            }?.let { if (key is ConfigKey.Domain) SourcePreferenceValue.Text(it.value.trim()) else it }
            else -> throw SourceInvalidArgumentException()
        }
        // The stored value changes the screen (and may change derived defaults); stale screens must re-read it.
        revision = UUID.randomUUID().toString()
        val status = when {
            accepted == null -> SourcePreferenceUpdateStatus.REJECTED
            config.put(key, accepted) -> SourcePreferenceUpdateStatus.ACCEPTED
            else -> SourcePreferenceUpdateStatus.PERSISTENCE_FAILED
        }
        return SourcePreferenceUpdate(status, screen())
    }

    private fun node(id: String, key: ConfigKey<*>): SourcePreferenceNode {
        val current = config[key]
        fun text(value: String?) = SourcePreferenceValue.Text(value.orEmpty())
        fun node(title: String, kind: SourcePreferenceKind, value: SourcePreferenceValue?, default: SourcePreferenceValue?,
            choices: List<SourcePreferenceChoice> = emptyList(), summary: String = "") =
            SourcePreferenceNode(id, key.key, title, summary, kind, enabled = true, visible = true, value = value,
                defaultValue = default, choices = choices)
        fun toggle(title: String, default: Boolean) = node(title, SourcePreferenceKind.TOGGLE,
            SourcePreferenceValue.Toggle(current as Boolean), SourcePreferenceValue.Toggle(default))
        return when (key) {
            is ConfigKey.Domain -> if (key.presetValues.size > 1) {
                node("域名", SourcePreferenceKind.CHOICE, text(current as String), text(key.defaultValue),
                    key.presetValues.map { SourcePreferenceChoice(it, it) })
            } else node("域名", SourcePreferenceKind.TEXT, text(current as String), text(key.defaultValue))
            is ConfigKey.UserAgent -> node("User-Agent", SourcePreferenceKind.TEXT, text(current as String), text(key.defaultValue))
            is ConfigKey.Text -> node(key.title, SourcePreferenceKind.TEXT, text(current as String), text(key.defaultValue))
            is ConfigKey.ShowSuspiciousContent -> toggle("显示可疑内容", key.defaultValue)
            is ConfigKey.SplitByTranslations -> toggle("按译者拆分章节", key.defaultValue)
            is ConfigKey.InterceptCloudflare -> toggle("拦截 Cloudflare 验证", key.defaultValue)
            is ConfigKey.Toggle -> toggle(key.title, key.defaultValue)
            is ConfigKey.PreferredImageServer -> node("首选图片服务器", SourcePreferenceKind.CHOICE, text(current as String?),
                text(key.defaultValue),
                key.presetValues.map { (value, label) -> SourcePreferenceChoice(label ?: value ?: "自动", value.orEmpty()) })
            is ConfigKey.PreferredLanguage -> node(key.title, SourcePreferenceKind.CHOICE, text(current as String),
                text(key.defaultValue), key.presetValues.map { (value, label) -> SourcePreferenceChoice(label, value) })
        }
    }

    private companion object {
        fun nodeId(key: ConfigKey<*>) = "config:${key.key}"
    }
}
