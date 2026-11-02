package com.lagradost.cloudstream3

import com.lagradost.cloudstream3.utils.Event

/**
 * The app-wide events of Cloudstream's `MainActivity` companion, which plugins fire after registering providers
 * (StreamPlay, StremioX) or changing their settings (CineStream). Kototoro has no such activity: it collects a
 * plugin's providers when `load()` returns, so nothing here needs a listener and firing an event is harmless.
 */
class MainActivity private constructor() {
	companion object {
		val afterPluginsLoadedEvent = Event<Boolean>()
		val mainPluginsLoadedEvent = Event<Boolean>()
		val afterRepositoryLoadedEvent = Event<Boolean>()
		val bookmarksUpdatedEvent = Event<Boolean>()
		val reloadHomeEvent = Event<Boolean>()
		val reloadLibraryEvent = Event<Boolean>()
		val reloadAccountEvent = Event<Boolean>()
	}
}
