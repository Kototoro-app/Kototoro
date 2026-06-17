package org.skepsun.kototoro.core.util.ext

import android.content.res.Resources
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.ui.model.DateTimeAgo
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.concurrent.TimeUnit

fun calculateTimeAgo(instant: Instant, showMonths: Boolean = false): DateTimeAgo? {
	val localDate = LocalDateTime.ofInstant(instant, ZoneId.systemDefault()).toLocalDate()
	val now = LocalDate.now()
	val diffDays = localDate.until(now, ChronoUnit.DAYS)

	return when {
		diffDays < 0 -> null // in future, probably a bug, not supported
		diffDays == 0L -> {
			if (instant.until(Instant.now(), ChronoUnit.MINUTES) < 3) DateTimeAgo.JustNow
			else DateTimeAgo.Today
		}

		diffDays == 1L -> DateTimeAgo.Yesterday
		diffDays < 6 -> DateTimeAgo.DaysAgo(diffDays.toInt())
		else -> {
			val diffMonths = localDate.until(now, ChronoUnit.MONTHS)
			if (showMonths && diffMonths <= 6) {
				DateTimeAgo.MonthsAgo(diffMonths.toInt())
			} else {
				DateTimeAgo.Absolute(localDate)
			}
		}
	}
}

fun calculateDateGroup(instant: Instant, showMonths: Boolean = false): DateTimeAgo? {
	val localDate = LocalDateTime.ofInstant(instant, ZoneId.systemDefault()).toLocalDate()
	val now = LocalDate.now()
	val diffDays = localDate.until(now, ChronoUnit.DAYS)

	return when {
		diffDays < 0 -> null
		diffDays == 0L -> {
			if (instant.until(Instant.now(), ChronoUnit.MINUTES) < 3) DateTimeAgo.JustNow
			else DateTimeAgo.Today
		}
		diffDays == 1L -> DateTimeAgo.Yesterday
		diffDays < 6 -> DateTimeAgo.DaysAgo(diffDays.toInt())
		else -> {
			val diffMonths = localDate.until(now, ChronoUnit.MONTHS)
			if (showMonths && diffMonths <= 6) {
				DateTimeAgo.MonthsAgo(diffMonths.toInt())
			} else {
				DateTimeAgo.Absolute(localDate)
			}
		}
	}
}

fun <T> List<T>.groupByDateBucket(
	instantOf: (T) -> Instant?,
	showMonths: Boolean = false,
): List<Pair<DateTimeAgo?, List<T>>> {
	if (isEmpty()) {
		return emptyList()
	}
	val grouped = LinkedHashMap<DateTimeAgo?, MutableList<T>>()
	val latestInstantByGroup = HashMap<DateTimeAgo?, Instant>()
	for (item in this) {
		val instant = instantOf(item)
		val group = instant?.let { calculateDateGroup(it, showMonths) }
		grouped.getOrPut(group) { ArrayList() }.add(item)
		if (instant != null) {
			val previous = latestInstantByGroup[group]
			if (previous == null || instant > previous) {
				latestInstantByGroup[group] = instant
			}
		}
	}
	return grouped.entries
		.sortedByDescending { latestInstantByGroup[it.key] ?: Instant.MIN }
		.map { it.key to it.value }
}

fun Long.toInstantOrNull() = if (this == 0L) null else Instant.ofEpochMilli(this)

fun Resources.formatDurationShort(millis: Long): String? {
	val hours = TimeUnit.MILLISECONDS.toHours(millis).toInt()
	val minutes = (TimeUnit.MILLISECONDS.toMinutes(millis) % 60).toInt()
	val seconds = (TimeUnit.MILLISECONDS.toSeconds(millis) % 60).toInt()
	return when {
		hours == 0 && minutes == 0 && seconds == 0 -> null
		hours != 0 && minutes != 0 -> getString(R.string.hours_minutes_short, hours, minutes)
		hours != 0 -> getString(R.string.hours_short, hours)
		minutes != 0 && seconds != 0 -> getString(R.string.minutes_seconds_short, minutes, seconds)
		minutes != 0 -> getString(R.string.minutes_short, minutes)
		else -> getString(R.string.seconds_short, seconds)
	}
}

fun LocalDate.toMillis(): Long = atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
