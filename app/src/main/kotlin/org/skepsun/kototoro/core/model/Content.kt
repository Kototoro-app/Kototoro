package org.skepsun.kototoro.core.model

import android.util.Log
import android.content.res.Resources
import android.net.Uri
import android.text.SpannableStringBuilder
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.collection.MutableObjectIntMap
import androidx.core.net.toUri
import androidx.core.os.LocaleListCompat
import androidx.core.text.buildSpannedString
import androidx.core.text.strikeThrough
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.ui.model.ContentOverride
import org.skepsun.kototoro.core.util.ext.iterator
import org.skepsun.kototoro.details.ui.model.ChapterListItem
import org.skepsun.kototoro.parsers.model.ContentRating
import org.skepsun.kototoro.parsers.model.Demographic
import org.skepsun.kototoro.parsers.model.Content
import org.skepsun.kototoro.parsers.model.ContentChapter
import org.skepsun.kototoro.parsers.model.ContentListFilter
import org.skepsun.kototoro.parsers.model.ContentState
import org.skepsun.kototoro.parsers.util.findById
import org.skepsun.kototoro.parsers.util.ifNullOrEmpty
import org.skepsun.kototoro.parsers.util.mapToSet
import com.google.android.material.R as materialR

@JvmName("mangaIds")
fun Collection<Content>.ids() = mapToSet { it.id }

fun Collection<Content>.distinctById() = distinctBy { it.id }

@JvmName("chaptersIds")
fun Collection<ContentChapter>.ids() = mapToSet { it.id }

fun Collection<ChapterListItem>.countChaptersByBranch(): Int {
	if (size <= 1) {
		return size
	}
	val acc = MutableObjectIntMap<String?>()
	for (item in this) {
		val branch = item.chapter.branch
		acc[branch] = acc.getOrDefault(branch, 0) + 1
	}
	var max = 0
	acc.forEachValue { x -> if (x > max) max = x }
	return max
}

@get:StringRes
val ContentState.titleResId: Int
	get() = when (this) {
		ContentState.ONGOING -> R.string.state_ongoing
		ContentState.FINISHED -> R.string.state_finished
		ContentState.ABANDONED -> R.string.state_abandoned
		ContentState.PAUSED -> R.string.state_paused
		ContentState.UPCOMING -> R.string.state_upcoming
		ContentState.RESTRICTED -> R.string.unavailable
	}

@get:DrawableRes
val ContentState.iconResId: Int
	get() = when (this) {
		ContentState.ONGOING -> R.drawable.ic_play
		ContentState.FINISHED -> R.drawable.ic_state_finished
		ContentState.ABANDONED -> R.drawable.ic_state_abandoned
		ContentState.PAUSED -> R.drawable.ic_action_pause
		ContentState.UPCOMING -> materialR.drawable.ic_clock_black_24dp
		ContentState.RESTRICTED -> R.drawable.ic_disable
	}

@get:StringRes
val ContentRating.titleResId: Int
	get() = when (this) {
		ContentRating.SAFE -> R.string.rating_safe
		ContentRating.SUGGESTIVE -> R.string.rating_suggestive
		ContentRating.ADULT -> R.string.rating_adult
	}

@get:StringRes
val Demographic.titleResId: Int
	get() = when (this) {
		Demographic.SHOUNEN -> R.string.demographic_shounen
		Demographic.SHOUJO -> R.string.demographic_shoujo
		Demographic.SEINEN -> R.string.demographic_seinen
		Demographic.JOSEI -> R.string.demographic_josei
		Demographic.KODOMO -> R.string.demographic_kodomo
		Demographic.NONE -> R.string.none
	}

fun Content.getPreferredBranch(history: ContentHistory?): String? {
	val ch = chapters
	if (ch.isNullOrEmpty()) {
		Log.d("ContentBranch", "getPreferredBranch: contentId=$id has no chapters")
		return null
	}
	if (history != null) {
		val currentChapter = ch.findById(history.chapterId)
		if (currentChapter != null) {
			Log.d(
				"ContentBranch",
				"getPreferredBranch: contentId=$id using history chapterId=${history.chapterId}, branch=${currentChapter.branch}",
			)
			return currentChapter.branch
		}
	}
	val groups = ch.groupBy { it.branch }
	if (groups.size == 1) {
		val onlyBranch = groups.keys.first()
		Log.d(
			"ContentBranch",
			"getPreferredBranch: contentId=$id single branch=$onlyBranch, count=${groups[onlyBranch]?.size ?: 0}",
		)
		return onlyBranch
	}
	for (locale in LocaleListCompat.getAdjustedDefault()) {
		val displayLanguage = locale.getDisplayLanguage(locale)
		val displayName = locale.getDisplayName(locale)
		val candidates = HashMap<String?, List<ContentChapter>>(3)
		for (branch in groups.keys) {
			if (branch != null && (
					branch.contains(displayLanguage, ignoreCase = true) ||
						branch.contains(displayName, ignoreCase = true)
					)
			) {
				candidates[branch] = groups[branch] ?: continue
			}
		}
		if (candidates.isNotEmpty()) {
			val matched = candidates.maxBy { it.value.size }.key
			Log.d(
				"ContentBranch",
				"getPreferredBranch: contentId=$id locale matched branch=$matched, groups=${groups.mapValues { it.value.size }}",
			)
			return matched
		}
	}
	val fallback = groups.maxByOrNull { it.value.size }?.key
	Log.d(
		"ContentBranch",
		"getPreferredBranch: contentId=$id fallback branch=$fallback, groups=${groups.mapValues { it.value.size }}",
	)
	return fallback
}

val Content.isLocal: Boolean
	get() = source.isLocal

val Content.isBroken: Boolean
	get() = source == UnknownContentSource

val Content.appUrl: Uri
	get() = "https://kototoro.app/manga".toUri()
		.buildUpon()
		.appendQueryParameter("source", source.name)
		.appendQueryParameter("name", title)
		.appendQueryParameter("url", url)
		.build()

fun Content.chaptersCount(): Int {
	if (chapters.isNullOrEmpty()) {
		return 0
	}
	val counters = MutableObjectIntMap<String?>()
	var max = 0
	chapters?.forEach { x ->
		val c = counters.getOrDefault(x.branch, 0) + 1
		counters[x.branch] = c
		if (max < c) {
			max = c
		}
	}
	return max
}

fun Content.isNsfw(): Boolean {
	if (contentRating == org.skepsun.kototoro.parsers.model.ContentRating.SAFE) return false
	
	val safeTags = setOf("safe", "all ages", "non-h", "sfw", "非h", "正常向", "全年龄", "全年龄向")
	val isExplicitlySafe = tags.any { it.title.lowercase() in safeTags }
	if (isExplicitlySafe) return false
	
	if (contentRating == org.skepsun.kototoro.parsers.model.ContentRating.ADULT) return true
	
	val hasAdultTag = tags.any { it.title.isAdultTagKeyword() || it.title.containsAdultTagKeyword() }
	if (hasAdultTag) return true
	
	return source.isNsfw()
}

fun ContentListFilter.getSummary() = buildSpannedString {
	if (!query.isNullOrEmpty()) {
		append(query)
		if (tags.isNotEmpty() || tagsExclude.isNotEmpty()) {
			append(' ')
			append('(')
			appendTagsSummary(this@getSummary)
			append(')')
		}
	} else {
		appendTagsSummary(this@getSummary)
	}
}

private fun SpannableStringBuilder.appendTagsSummary(filter: ContentListFilter) {
	var isFirst = true
	val separator = ", "
	for (tag in filter.tags) {
		if (isFirst) {
			isFirst = false
		} else {
			append(separator)
		}
		append(tag.title)
	}
	for (tag in filter.tagsExclude) {
		if (isFirst) {
			isFirst = false
		} else {
			append(separator)
		}
		strikeThrough {
			append(tag.title)
		}
	}
}

fun ContentChapter.getLocalizedTitle(resources: Resources, index: Int = -1): String {
	title?.let {
		if (it.isNotBlank()) {
			return it
		}
	}
	val num = numberString()
	val vol = volumeString()
	return when {
		num != null && vol != null -> resources.getString(R.string.chapter_volume_number, vol, num)
		num != null -> resources.getString(R.string.chapter_number, num)
		index > 0 -> resources.getString(
			R.string.chapters_time_pattern,
			resources.getString(R.string.unnamed_chapter),
			index.toString(),
		)

		else -> resources.getString(R.string.unnamed_chapter)
	}
}

fun Content.withOverride(override: ContentOverride?) = if (override != null) {
	copy(
		title = override.title.ifNullOrEmpty { title },
		coverUrl = override.coverUrl.ifNullOrEmpty { coverUrl },
		largeCoverUrl = override.coverUrl.ifNullOrEmpty { largeCoverUrl },
		contentRating = override.contentRating ?: contentRating,
	)
} else {
	this
}
