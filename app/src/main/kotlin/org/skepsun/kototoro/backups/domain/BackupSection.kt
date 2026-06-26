package org.skepsun.kototoro.backups.domain

import java.util.Locale
import java.util.zip.ZipEntry

enum class BackupSection(
	val entryName: String,
) {

	INDEX("index"),
	HISTORY("history"),
	CATEGORIES("categories"),
	FAVOURITES("favourites"),
	SETTINGS("settings"),
	SETTINGS_READER_GRID("reader_grid"),
	BOOKMARKS("bookmarks"),
	SOURCES("sources"),
	EXTENSION_REPOS("extension_repos"),
	SCROBBLING("scrobbling"),
	STATS("statistics"),
	SAVED_FILTERS("saved_filters"),
	AUTH("auth"),
	ENTITY_GRAPH_ENTITIES("entity_graph_entities"),
	ENTITY_GRAPH_BINDINGS("entity_graph_bindings"),
	ENTITY_GRAPH_RELATIONS("entity_graph_relations"),
	ENTITY_GRAPH_PREFS("entity_graph_prefs"),
	TRACKS("tracks"),
	TRACK_LOGS("track_logs"),
	PROJECTIONS("projections"),
	WORK_HISTORY("work_history"),
	WORK_FAVOURITES("work_favourites"),
	WORK_STATS("work_stats"),
	;

	companion object {

		fun of(entry: ZipEntry): BackupSection? {
			val name = entry.name.lowercase(Locale.ROOT)
			return entries.find { x -> x.entryName == name }
		}
	}
}
