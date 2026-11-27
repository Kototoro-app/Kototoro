package org.skepsun.kototoro.core.network

import javax.inject.Qualifier

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class BaseHttpClient

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ContentHttpClient

/** Short-timeout client for the tracker webhook: one fire-and-forget POST, never a long call. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class TrackerWebhookClient
