package org.skepsun.kototoro.core.source

/**
 * One entry of a preference section: either a source's own preference or the label of a group nested inside another
 * group. Both hosts that draw the protocol tree (the Windows screen today, an Android bridge over androidx
 * `Preference`s later) need the same folding, so it lives next to the DTOs instead of inside one UI module.
 */
sealed interface SourcePreferenceEntry {
    val id: String

    data class Row(val node: SourcePreferenceNode) : SourcePreferenceEntry {
        override val id: String get() = node.id
    }

    data class Label(override val id: String, val title: String) : SourcePreferenceEntry
}

/** One titled section of a source's settings; [entries] are the visible leaves plus nested group labels. */
data class SourcePreferenceSection(
    val id: String,
    val title: String,
    val entries: List<SourcePreferenceEntry>,
) {
    /** The leaf nodes of this section, in drawing order; dialog hosts look a row up by id. */
    val rows: List<SourcePreferenceNode> get() = entries.filterIsInstance<SourcePreferenceEntry.Row>().map { it.node }
}

/** Kototoro's own rows (User-Agent, domain) sort ahead of the source's, exactly as they lead on Android. */
private const val HOST_NODE_PREFIX = "host:"

/**
 * Folds the protocol tree into the sections Android builds from androidx Preferences: Kototoro's own `host:` rows
 * (User-Agent, domain) lead a section titled with Android's "settings" string, then every titled GROUP starts a
 * section of its own. An untitled GROUP joins the section being built, exactly as Android flattens a `PreferenceGroup`
 * into the rows around it, but a GROUP nested inside another one keeps its title as a label row — Android's
 * `SourceSettingsGroupLabelRowUiState` — so a source whose settings nest "Account → Advanced" does not lose that
 * structure in one flat card. Invisible nodes drop out and a section without rows disappears.
 */
fun sourcePreferenceSections(
    nodes: List<SourcePreferenceNode>,
    fallbackTitle: String,
): List<SourcePreferenceSection> {
    val sections = mutableListOf<SourcePreferenceSection>()
    val loose = mutableListOf<SourcePreferenceEntry>()
    var defaultSections = 0
    fun flushLoose() {
        if (loose.isEmpty()) return
        // A group between two loose rows produces Android's second "settings" section, so the id has to be unique.
        sections += SourcePreferenceSection("section:default:${defaultSections++}", fallbackTitle, loose.toList())
        loose.clear()
    }
    // A stable sort keeps the source's own order inside both buckets; Kototoro's rows lead, as they do on Android.
    val ordered = nodes.filter { it.visible }.sortedBy { if (it.id.startsWith(HOST_NODE_PREFIX)) 0 else 1 }
    for (node in ordered) {
        if (node.kind == SourcePreferenceKind.GROUP && node.title.isNotBlank()) {
            flushLoose()
            val entries = node.children.flatMap(::groupEntries)
            if (entries.isNotEmpty()) sections += SourcePreferenceSection("section:${node.id}", node.title, entries)
        } else {
            loose += groupEntries(node)
        }
    }
    flushLoose()
    return sections
}

/**
 * A GROUP carries no value of its own, so it contributes its visible children; a nested GROUP keeps a label row first,
 * which is how Android draws a group inside a category.
 */
private fun groupEntries(node: SourcePreferenceNode): List<SourcePreferenceEntry> = when {
    !node.visible -> emptyList()
    node.kind != SourcePreferenceKind.GROUP -> listOf(SourcePreferenceEntry.Row(node))
    else -> {
        val children = node.children.flatMap(::groupEntries)
        val label = node.title.takeIf(String::isNotBlank)
            ?.takeIf { children.isNotEmpty() }
            ?.let { listOf(SourcePreferenceEntry.Label("label:${node.id}", it)) }
            .orEmpty()
        label + children
    }
}

/** A source may report a control without a title; its key is the closest thing to a name it still has. */
fun sourcePreferenceRowTitle(node: SourcePreferenceNode): String = node.title.ifBlank { node.key.orEmpty() }

/** Android shows a choice's label, not its raw value. */
fun sourcePreferenceChoiceLabel(node: SourcePreferenceNode): String? {
    val value = (node.value as? SourcePreferenceValue.Text)?.value ?: return null
    return node.choices.firstOrNull { it.value == value }?.title ?: value
}

/** Android joins the selected multi-choice labels the same way, and shows nothing when the set is empty. */
fun sourcePreferenceMultiChoiceLabel(node: SourcePreferenceNode): String? {
    val values = (node.value as? SourcePreferenceValue.TextSet)?.values.orEmpty()
    if (values.isEmpty()) return null
    return node.choices.filter { it.value in values }.joinToString(", ") { it.title }.ifEmpty { values.size.toString() }
}

/** A secret keeps its length out of sight; a blank text row shows no value at all, like Android's empty summary. */
fun sourcePreferenceTextLabel(node: SourcePreferenceNode): String? {
    val value = (node.value as? SourcePreferenceValue.Text)?.value.orEmpty()
    return when {
        value.isEmpty() -> null
        node.isSecret -> "•".repeat(value.length.coerceAtMost(12))
        else -> value
    }
}

/** The value Android prints in front of a row's chevron, by control kind; `null` for rows without a value. */
fun sourcePreferenceValueLabel(node: SourcePreferenceNode): String? = when (node.kind) {
    SourcePreferenceKind.CHOICE -> sourcePreferenceChoiceLabel(node)
    SourcePreferenceKind.MULTI_CHOICE -> sourcePreferenceMultiChoiceLabel(node)
    SourcePreferenceKind.TEXT -> sourcePreferenceTextLabel(node)
    else -> null
}
