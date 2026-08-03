package org.skepsun.kototoro.settings.sources.blacklist

import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.plus
import org.skepsun.kototoro.core.model.GlobalTagBlacklist
import org.skepsun.kototoro.core.model.KototoroTaxonomy
import org.skepsun.kototoro.core.model.TaxonomyCategory
import org.skepsun.kototoro.core.model.TaxonomyTag
import org.skepsun.kototoro.core.parser.ContentDataRepository
import org.skepsun.kototoro.core.prefs.AppSettings
import org.skepsun.kototoro.core.prefs.observeAsFlow
import org.skepsun.kototoro.core.ui.BaseViewModel
import java.util.Locale
import javax.inject.Inject

data class GlobalTagBlacklistItem(
	val key: String,
	val label: String,
	val category: TaxonomyCategory,
	val aliases: List<String> = emptyList(),
)

data class GlobalTagBlacklistUiState(
	val tags: List<GlobalTagBlacklistItem> = emptyList(),
	val selectedItems: List<GlobalTagBlacklistItem> = emptyList(),
	val selectedTags: Set<String> = emptySet(),
)

@HiltViewModel
class GlobalTagBlacklistViewModel @Inject constructor(
	private val settings: AppSettings,
	contentDataRepository: ContentDataRepository,
) : BaseViewModel() {

	val searchQuery = MutableStateFlow("")

	init {
		val migratedTags = settings.globalTagBlacklist.flatMapTo(LinkedHashSet(), ::migrateSelection)
		if (migratedTags != settings.globalTagBlacklist) {
			settings.globalTagBlacklist = migratedTags
		}
	}

	val uiState = combine(
		contentDataRepository.observeAllTagTitles(),
		settings.observeAsFlow(AppSettings.KEY_GLOBAL_TAG_BLACKLIST) { globalTagBlacklist },
		searchQuery,
	) { availableRawTags, selectedTags, query ->
		val locale = Locale.getDefault()
		val normalizedQuery = KototoroTaxonomy.normalize(query)
		val standardTags = KototoroTaxonomy.tags.asSequence().map { tag -> tag.toListItem(locale) }
		val rawTagsByKey = LinkedHashMap<String, String>()
		(availableRawTags + KototoroTaxonomy.knownSourceTags).forEach { rawTag ->
			if (KototoroTaxonomy.resolve(rawTag).isEmpty()) {
				rawTagsByKey.putIfAbsent(GlobalTagBlacklist.rawTagKey(rawTag), rawTag.trim())
			}
		}
		selectedTags.filter { it.startsWith(GlobalTagBlacklist.RAW_TAG_PREFIX) }.forEach { key ->
			rawTagsByKey.putIfAbsent(key, key.removePrefix(GlobalTagBlacklist.RAW_TAG_PREFIX))
		}
		val rawTags = rawTagsByKey.asSequence().map { (key, label) ->
				GlobalTagBlacklistItem(
					key = key,
					label = label,
					category = TaxonomyCategory.RAW,
				)
			}
		val allTags = (standardTags + rawTags)
				.sortedWith(compareBy(GlobalTagBlacklistItem::category, GlobalTagBlacklistItem::label))
				.toList()
		GlobalTagBlacklistUiState(
			tags = allTags.filter { tag ->
				tag.key !in selectedTags && tag.matches(normalizedQuery)
			},
			selectedItems = allTags.filter { it.key in selectedTags },
			selectedTags = selectedTags,
		)
	}.stateIn(
		scope = viewModelScope + Dispatchers.Default,
		started = SharingStarted.Eagerly,
		initialValue = GlobalTagBlacklistUiState(selectedTags = settings.globalTagBlacklist),
	)

	fun toggleTag(key: String) {
		val updated = settings.globalTagBlacklist.toMutableSet()
		if (!updated.add(key)) {
			updated -= key
		}
		settings.globalTagBlacklist = updated
	}

	fun addQuery() {
		val rawTag = searchQuery.value.trim()
		if (rawTag.isEmpty()) {
			return
		}
		val resolved = KototoroTaxonomy.resolve(rawTag)
		val updated = settings.globalTagBlacklist.toMutableSet()
		if (resolved.isEmpty()) {
			updated += GlobalTagBlacklist.rawTagKey(rawTag)
		} else {
			updated += resolved.map(TaxonomyTag::id)
		}
		settings.globalTagBlacklist = updated
		searchQuery.value = ""
	}

	fun clear() {
		settings.globalTagBlacklist = emptySet()
	}

	private fun migrateSelection(selection: String): Set<String> = when {
		selection.startsWith(GlobalTagBlacklist.RAW_TAG_PREFIX) -> setOf(GlobalTagBlacklist.rawTagKey(
			selection.removePrefix(GlobalTagBlacklist.RAW_TAG_PREFIX),
		))
		KototoroTaxonomy.find(selection) != null -> setOf(selection)
		else -> KototoroTaxonomy.resolve(selection).mapTo(LinkedHashSet(), TaxonomyTag::id)
			.ifEmpty { setOf(GlobalTagBlacklist.rawTagKey(selection)) }
	}

	private fun TaxonomyTag.toListItem(locale: Locale): GlobalTagBlacklistItem {
		val displayLabel = displayName(locale)
		return GlobalTagBlacklistItem(
			key = id,
			label = displayLabel,
			category = category,
			aliases = buildList {
				add(englishLabel)
				add(chineseLabel)
				add(traditionalChineseLabel)
				addAll(aliases.sortedWith(String.CASE_INSENSITIVE_ORDER))
			}
				.distinctBy(KototoroTaxonomy::normalize)
				.filterNot {
					KototoroTaxonomy.normalize(it) == KototoroTaxonomy.normalize(displayLabel)
				},
		)
	}

	private fun GlobalTagBlacklistItem.matches(normalizedQuery: String): Boolean =
		normalizedQuery.isEmpty() ||
			KototoroTaxonomy.normalize(key).contains(normalizedQuery) ||
			KototoroTaxonomy.normalize(label).contains(normalizedQuery) ||
			aliases.any { KototoroTaxonomy.normalize(it).contains(normalizedQuery) }
}
