package org.skepsun.kototoro.core

import org.skepsun.kototoro.cloudstream.runtime.CloudstreamRuntimeManager

object BaseAppHolder {
    @Volatile
    private var runtimeManager: CloudstreamRuntimeManager? = null

    fun setCloudstreamRuntimeManager(manager: CloudstreamRuntimeManager) {
        runtimeManager = manager
    }

    fun get(): CloudstreamRuntimeManager? = runtimeManager
}
