package org.skepsun.kototoro.migration.domain

/** User data carried from the old entry to the new one. [CATEGORIES] is always on. */
enum class MigrationDataFlag(val bit: Int) {
    CATEGORIES(1),
    PROGRESS(1 shl 1),
    TRACKING(1 shl 2),
    NOTES(1 shl 3),
    STATS(1 shl 4),
    ;

    companion object {
        val ALL: Set<MigrationDataFlag> = entries.toSet()

        fun toBits(flags: Set<MigrationDataFlag>): Int = flags.fold(0) { acc, flag -> acc or flag.bit }

        fun fromBits(bits: Int): Set<MigrationDataFlag> =
            entries.filterTo(mutableSetOf()) { bits and it.bit != 0 } + CATEGORIES
    }
}

/** [REPLACE] removes the old entry from favourites and history; [COPY] keeps it. */
enum class MigrationMode { REPLACE, COPY }

/** [FIRST_HIT] stops at the first source with a good enough match; [MOST_CHAPTERS] searches all sources. */
enum class MatchMode { FIRST_HIT, MOST_CHAPTERS }
