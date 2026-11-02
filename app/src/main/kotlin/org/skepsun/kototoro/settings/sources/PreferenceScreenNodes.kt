package org.skepsun.kototoro.settings.sources

import android.content.Context
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.MultiSelectListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceGroup
import androidx.preference.PreferenceScreen
import androidx.preference.TwoStatePreference
import org.skepsun.kototoro.core.source.SourcePreferenceChoice
import org.skepsun.kototoro.core.source.SourcePreferenceKind
import org.skepsun.kototoro.core.source.SourcePreferenceNode
import org.skepsun.kototoro.core.source.SourcePreferenceValue

/**
 * The androidx `PreferenceScreen` a Mihon/Aniyomi/Tsundoku extension builds, as the source protocol's preference tree
 * (`SourcePreferenceNode`), so Android's per-source page can hand it to the shared `SourcePreferencesContent` the
 * Windows host already draws instead of mapping every control to its own row type.
 *
 * Only presentation is bridged. A write still goes through the extension's own `callChangeListener`, so an
 * `onPreferenceChange` that returns false keeps the veto it has today, and a click still runs the extension's listener
 * or `Intent`; nothing here reaches into `SourceSettings` or the source runtime.
 *
 * The categories become titled GROUP nodes (the shared folding turns them into sections, and a group nested in a group
 * into a label row), every control keeps its key as the node id so a row can be found again for its dialog, and a
 * preference Android cannot edit (a `fragment`, an invisible or non-selectable row) stays visible as the UNSUPPORTED or
 * INFO row it is today.
 */
internal class PreferenceScreenNodes(private val context: Context) {

    private val targets = LinkedHashMap<String, Preference>()
    private var generatedIds = 0

    fun nodes(screen: PreferenceScreen, onUnsupported: (Preference) -> Unit = {}): List<SourcePreferenceNode> {
        targets.clear()
        generatedIds = 0
        return screen.children(onUnsupported)
    }

    fun target(nodeId: String): Preference? = targets[nodeId]

    /** Writes [value] through the extension's own veto; false means the row rejected the change or is another kind. */
    fun write(node: SourcePreferenceNode, value: SourcePreferenceValue): Boolean {
        val preference = targets[node.id] ?: return false
        return when (value) {
            is SourcePreferenceValue.Toggle -> (preference as? TwoStatePreference)?.accept(value.value) ?: false
            is SourcePreferenceValue.TextSet -> (preference as? MultiSelectListPreference)?.accept(value.values) ?: false
            is SourcePreferenceValue.Text -> preference.acceptText(value.value)
            else -> false
        }
    }

    /** ACTION rows: the extension's listener first, its `Intent` second, exactly as the old action row behaved. */
    fun action(node: SourcePreferenceNode) {
        val preference = targets[node.id] ?: return
        val handled = preference.onPreferenceClickListener?.onPreferenceClick(preference) == true
        if (!handled) {
            preference.intent?.let(context::startActivity)
        }
    }

    private fun PreferenceGroup.children(onUnsupported: (Preference) -> Unit): List<SourcePreferenceNode> {
        val nodes = mutableListOf<SourcePreferenceNode>()
        for (index in 0 until preferenceCount) {
            val child = getPreference(index) ?: continue
            child.toNode(onUnsupported)?.let(nodes::add)
        }
        return nodes
    }

    private fun Preference.toNode(onUnsupported: (Preference) -> Unit): SourcePreferenceNode? {
        val id = register(this)
        val title = title?.toString().orEmpty()
        val summary = summary?.toString().orEmpty()
        val common = Common(id, key, title, summary, isEnabled, isVisible)
        return when {
            this is PreferenceGroup -> common.group(children(onUnsupported))
            fragment != null -> {
                onUnsupported(this)
                common.node(SourcePreferenceKind.UNSUPPORTED)
            }

            this is TwoStatePreference -> common.node(
                kind = SourcePreferenceKind.TOGGLE,
                value = SourcePreferenceValue.Toggle(isChecked),
            )

            this is ListPreference -> common.node(
                kind = SourcePreferenceKind.CHOICE,
                value = SourcePreferenceValue.Text(value.orEmpty()),
                choices = choices(entries, entryValues),
            )

            this is MultiSelectListPreference -> common.node(
                kind = SourcePreferenceKind.MULTI_CHOICE,
                value = SourcePreferenceValue.TextSet(values),
                choices = choices(entries, entryValues),
            )

            this is EditTextPreference -> common.node(
                kind = SourcePreferenceKind.TEXT,
                value = SourcePreferenceValue.Text(text.orEmpty()),
                isSecret = isLikelyPassword(),
            )
            // A row that cannot be clicked and has nowhere to go is a header, as Android's label row was.
            !isSelectable && intent == null && onPreferenceClickListener == null -> common.node(SourcePreferenceKind.INFO)
            else -> common.node(kind = SourcePreferenceKind.ACTION, value = SourcePreferenceValue.Action())
        }
    }

    private fun choices(entries: Array<CharSequence>?, entryValues: Array<CharSequence>?): List<SourcePreferenceChoice> {
        val values = entryValues ?: return emptyList()
        return entries.orEmpty().mapIndexedNotNull { index, entry ->
            values.getOrNull(index)?.toString()?.let { SourcePreferenceChoice(title = entry?.toString().orEmpty(), value = it) }
        }
    }

    private fun EditTextPreference.isLikelyPassword(): Boolean {
        val lowerKey = key?.lowercase().orEmpty()
        val lowerTitle = title?.toString()?.lowercase().orEmpty()
        return "password" in lowerKey || "password" in lowerTitle
    }

    private fun register(preference: Preference): String {
        val key = preference.key?.takeIf(String::isNotBlank)
            ?: "node:${preference.javaClass.simpleName}:${generatedIds++}"
        var id = key
        var duplicate = 2
        while (targets.containsKey(id)) {
            id = "$key#$duplicate"
            duplicate += 1
        }
        targets[id] = preference
        return id
    }

    private fun TwoStatePreference.accept(checked: Boolean): Boolean =
        callChangeListener(checked).also { if (it) isChecked = checked }

    private fun MultiSelectListPreference.accept(values: Set<String>): Boolean =
        callChangeListener(values).also { if (it) this.values = values }

    private fun Preference.acceptText(text: String): Boolean = when (this) {
        is ListPreference -> callChangeListener(text).also { if (it) value = text }
        is EditTextPreference -> callChangeListener(text).also { if (it) this.text = text }
        else -> false
    }

    /** The parts every node shares; kept out of the `when` so each branch reads as the control it maps. */
    private data class Common(
        val id: String,
        val key: String?,
        val title: String,
        val summary: String,
        val enabled: Boolean,
        val visible: Boolean,
    ) {
        fun group(children: List<SourcePreferenceNode>): SourcePreferenceNode = SourcePreferenceNode(
            id = id,
            key = key,
            title = title,
            summary = summary,
            kind = SourcePreferenceKind.GROUP,
            enabled = enabled,
            visible = visible,
            children = children,
        )

        fun node(
            kind: SourcePreferenceKind,
            value: SourcePreferenceValue? = null,
            choices: List<SourcePreferenceChoice> = emptyList(),
            isSecret: Boolean = false,
        ): SourcePreferenceNode = SourcePreferenceNode(
            id = id,
            key = key,
            title = title,
            summary = summary,
            kind = kind,
            enabled = enabled,
            visible = visible,
            value = value,
            choices = choices,
            isSecret = isSecret,
        )
    }
}
