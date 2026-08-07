@file:JvmName("ContextHelper_jvmKt")

package com.lagradost.api

import android.content.Context
import java.lang.ref.WeakReference

private var contextRef: WeakReference<Context>? = null

fun getContext(): Any? = contextRef?.get()

fun setContext(context: Any) {
	((context as? WeakReference<*>)?.get() as? Context)?.let { actualContext ->
		contextRef = WeakReference(actualContext)
	}
}
