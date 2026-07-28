package org.skepsun.kototoro.core.ui

import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import org.skepsun.kototoro.core.exceptions.resolve.ExceptionResolver
import org.skepsun.kototoro.core.prefs.AppSettings
import org.skepsun.kototoro.core.ui.theme.OnlineFontLoader

@EntryPoint
@InstallIn(SingletonComponent::class)
interface BaseActivityEntryPoint {

	val settings: AppSettings

	val exceptionResolverFactory: ExceptionResolver.Factory

	val onlineFontLoader: OnlineFontLoader
}
