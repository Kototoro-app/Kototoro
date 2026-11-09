package com.lagradost.cloudstream3.utils

import android.widget.ImageView
import coil3.load
import coil3.network.NetworkHeaders
import coil3.network.httpHeaders

/** Cloudstream's image loader, which plugins use for pictures in their own screens; Coil here as there. */
object ImageLoader {
	fun ImageView.loadImage(
		imageData: String?,
		headers: Map<String, String>? = null,
		@Suppress("UNUSED_PARAMETER") builder: (Any) -> Unit = {},
	) {
		load(imageData) {
			if (!headers.isNullOrEmpty()) {
				httpHeaders(NetworkHeaders.Builder().apply { headers.forEach { (key, value) -> set(key, value) } }.build())
			}
		}
	}
}
