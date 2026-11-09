package com.lagradost.cloudstream3

import androidx.appcompat.app.AppCompatActivity
import com.lagradost.cloudstream3.utils.Event

/**
 * Cloudstream's activity as plugins see it: they get it from `Plugin.load(context)` and cast to it (or to
 * `AppCompatActivity`) to open dialogs through its fragment manager. Kototoro's base activities extend it so that
 * the activity handed to a plugin is one; on Windows the host hands plugins an instance standing in for it.
 *
 * Its companion holds the app-wide events plugins fire after registering providers (StreamPlay, StremioX) or
 * changing their settings (CineStream). Kototoro collects a plugin's providers when `load()` returns, so nothing
 * here needs a listener and firing an event is harmless.
 *
 * Shared with the Windows host as is (`:cloudstream-desktop` copies it), so it uses nothing Android-only.
 */
abstract class MainActivity : AppCompatActivity() {
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
