package org.skepsun.kototoro.browser.cloudflare

import org.skepsun.kototoro.browser.BrowserCallback

interface CloudFlareCallback : BrowserCallback {

	override fun onTitleChanged(title: CharSequence, subtitle: CharSequence?) = Unit

	fun onPageLoaded()

	fun onCheckPassed()

	fun onLoopDetected()

	fun onMainFrameError() = Unit
}
