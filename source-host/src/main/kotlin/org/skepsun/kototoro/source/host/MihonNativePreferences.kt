package org.skepsun.kototoro.source.host

import org.skepsun.kototoro.core.source.*
import java.lang.reflect.InvocationTargetException
import java.util.UUID

/** Executes the initialized compatibility runtime's controls. No source key is written around its listener. */
internal class MihonNativePreferences(private val lease: MihonJarRegistry.SourceLease, context: Any) {
    private val preferences = requireNotNull(MihonReflection.call(lease.instance, "getSourcePreferences"))
    private val controls = linkedMapOf<String, Any>()
    private val root: Any
    private var revision = UUID.randomUUID().toString()

    init {
        val type = lease.loader.loadClass("androidx.preference.PreferenceScreen")
        val contextClass = lease.loader.loadClass("android.content.Context")
        require(contextClass.isInstance(context)) { "Preference context does not belong to the compatibility runtime" }
        root = try { type.getConstructor(contextClass).newInstance(context) }
        catch (error: InvocationTargetException) { throw error.targetException }
        MihonReflection.call(root, "setSharedPreferences", preferences)
        MihonReflection.call(lease.instance, "setupPreferenceScreen", root)
        // Some sources rebind controls or add them late; bind the complete tree after setup too.
        collect(root, "", 0)
    }

    fun definition(): SourcePreferenceScreen {
        controls.clear()
        collect(root, "", 0)
        val nodes = children(root).mapIndexed { index, node -> describe(node, "pref:$index", true, true) }
        val keys = controls.values.mapNotNull { MihonReflection.call(it, "getKey") as? String }
            .groupingBy { it }.eachCount()
        fun unique(node: SourcePreferenceNode): SourcePreferenceNode = node.copy(
            kind = if (node.key != null && keys[node.key] != 1) SourcePreferenceKind.UNSUPPORTED else node.kind,
            children = node.children.map(::unique),
        )
        return SourcePreferenceScreen(lease.descriptor.source, revision, nodes.map(::unique))
    }

    fun update(expectedRevision: String, id: String, value: SourcePreferenceValue): SourcePreferenceUpdate {
        if (expectedRevision != revision) throw SourceInvalidArgumentException()
        val node = flatten(definition().nodes).firstOrNull { it.id == id } ?: throw SourceInvalidArgumentException()
        if (node.kind == SourcePreferenceKind.UNSUPPORTED || node.kind == SourcePreferenceKind.INFO ||
            node.kind == SourcePreferenceKind.GROUP) throw SourceOperationUnsupportedException()
        if (!node.enabled || !node.visible || node.key == null) throw SourceInvalidArgumentException()
        val nativeValue: Any = when (node.kind) {
            SourcePreferenceKind.TEXT -> (value as? SourcePreferenceValue.Text)?.value
            SourcePreferenceKind.TOGGLE -> (value as? SourcePreferenceValue.Toggle)?.value
            SourcePreferenceKind.CHOICE -> (value as? SourcePreferenceValue.Text)?.value?.takeIf { selected ->
                node.choices.any { it.value == selected }
            }
            SourcePreferenceKind.MULTI_CHOICE -> (value as? SourcePreferenceValue.TextSet)?.values?.takeIf { selected ->
                selected.all { choice -> node.choices.any { it.value == choice } }
            }?.toMutableSet()
            else -> null
        } ?: throw SourceInvalidArgumentException()
        val control = requireNotNull(controls[id])
        // An executable listener can reject, modify another control, write another setting, or throw.
        // Invalidate its revision even on rejection/failure; effects cannot be safely rolled back by the host.
        revision = UUID.randomUUID().toString()
        val accepted = MihonReflection.call(control, "callChangeListener", nativeValue) as Boolean
        val status = if (!accepted) SourcePreferenceUpdateStatus.REJECTED else {
            MihonReflection.call(control, "saveNewValue", nativeValue)
            val editor = requireNotNull(MihonReflection.call(preferences, "edit"))
            // Native saveNewValue uses apply. Empty commit flushes/retries without bypassing the control.
            if (MihonReflection.call(editor, "commit") as Boolean) SourcePreferenceUpdateStatus.ACCEPTED
            else SourcePreferenceUpdateStatus.PERSISTENCE_FAILED
        }
        return SourcePreferenceUpdate(status, definition())
    }

    private fun collect(parent: Any, prefix: String, depth: Int) {
        require(depth <= 16) { "Preference tree is too deep" }
        children(parent).forEachIndexed { index, node ->
            require(controls.size < 1024) { "Preference tree is too large" }
            val id = if (prefix.isEmpty()) "pref:$index" else "$prefix/$index"
            controls[id] = node
            MihonReflection.call(node, "setSharedPreferences", preferences)
            collect(node, id, depth + 1)
        }
    }

    private fun describe(node: Any, id: String, parentEnabled: Boolean, parentVisible: Boolean): SourcePreferenceNode {
        val enabled = parentEnabled && (MihonReflection.call(node, "isEnabled") as Boolean)
        val visible = parentVisible && (MihonReflection.call(node, "getVisible") as Boolean)
        var kind = kind(node)
        val choices = if (kind == SourcePreferenceKind.CHOICE || kind == SourcePreferenceKind.MULTI_CHOICE) {
            val entries = MihonReflection.call(node, "getEntries") as? Array<*>
            val values = MihonReflection.call(node, "getEntryValues") as? Array<*>
            if (entries == null || values == null || entries.size != values.size || values.any { it == null } ||
                values.map { it.toString() }.distinct().size != values.size) {
                kind = SourcePreferenceKind.UNSUPPORTED
                emptyList()
            } else entries.mapIndexed { index, title ->
                SourcePreferenceChoice(title?.toString().orEmpty(), values[index].toString())
            }
        } else emptyList()
        if (kind == SourcePreferenceKind.TEXT && MihonReflection.optional(node, "getOnBindEditTextListener") != null) {
            // Binding can configure passwords, numeric constraints or other Android input behavior.
            kind = SourcePreferenceKind.UNSUPPORTED
        }
        val editable = kind in setOf(SourcePreferenceKind.TEXT, SourcePreferenceKind.TOGGLE,
            SourcePreferenceKind.CHOICE, SourcePreferenceKind.MULTI_CHOICE)
        return SourcePreferenceNode(
            id, MihonReflection.call(node, "getKey") as? String,
            MihonReflection.call(node, "getTitle")?.toString().orEmpty(),
            MihonReflection.call(node, "getSummary")?.toString().orEmpty(),
            kind, enabled, visible,
            value = if (editable) value(MihonReflection.call(node, "getCurrentValue")) else null,
            defaultValue = if (editable) value(MihonReflection.call(node, "getDefaultValue")) else null,
            choices = choices,
            children = children(node).mapIndexed { index, child -> describe(child, "$id/$index", enabled, visible) },
        )
    }

    private fun kind(node: Any): SourcePreferenceKind {
        var type: Class<*>? = node.javaClass
        while (type != null) {
            when (type.name) {
                "androidx.preference.ListPreference" -> return SourcePreferenceKind.CHOICE
                "androidx.preference.MultiSelectListPreference" -> return SourcePreferenceKind.MULTI_CHOICE
                "androidx.preference.EditTextPreference" -> return SourcePreferenceKind.TEXT
                "androidx.preference.SwitchPreferenceCompat", "androidx.preference.CheckBoxPreference" ->
                    return SourcePreferenceKind.TOGGLE
                "androidx.preference.PreferenceScreen", "androidx.preference.PreferenceCategory",
                "androidx.preference.PreferenceGroup" -> return SourcePreferenceKind.GROUP
            }
            type = type.superclass
        }
        return if (node.javaClass.name == "androidx.preference.Preference" &&
            MihonReflection.call(node, "getKey") == null) {
            SourcePreferenceKind.INFO
        } else SourcePreferenceKind.UNSUPPORTED
    }

    private fun children(node: Any): List<Any> {
        val list = MihonReflection.optional(node, "getPreferences") as? List<*>
        if (list != null) {
            require(list.size <= 1024) { "Preference tree is too large" }
            return list.map(::requireNotNull)
        }
        val count = MihonReflection.optional(node, "getPreferenceCount") as? Int ?: return emptyList()
        require(count in 0..1024) { "Invalid preference count" }
        return (0 until count).map { requireNotNull(MihonReflection.call(node, "getPreference", it)) }
    }

    private fun value(value: Any?): SourcePreferenceValue? = when (value) {
        null -> null
        is String -> SourcePreferenceValue.Text(value)
        is Boolean -> SourcePreferenceValue.Toggle(value)
        is Int -> SourcePreferenceValue.Integer(value)
        is Long -> SourcePreferenceValue.LongInteger(value)
        is Float -> SourcePreferenceValue.FloatBits(value.toRawBits())
        is Set<*> -> SourcePreferenceValue.TextSet(value.map { it as String }.toSet())
        else -> throw SourceOperationUnsupportedException()
    }

    private fun flatten(nodes: List<SourcePreferenceNode>): List<SourcePreferenceNode> =
        nodes.flatMap { listOf(it) + flatten(it.children) }

    companion object {
        /** Manga and novel sources implement `ConfigurableSource`, anime ones `ConfigurableAnimeSource`; same controls. */
        fun supports(lease: MihonJarRegistry.SourceLease, abi: NativeAbi = NativeAbi.MIHON): Boolean = try {
            val name = if (abi == NativeAbi.ANIYOMI) "eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource"
                else "eu.kanade.tachiyomi.source.ConfigurableSource"
            lease.loader.loadClass(name).isInstance(lease.instance)
        } catch (_: ClassNotFoundException) { false }
    }
}
