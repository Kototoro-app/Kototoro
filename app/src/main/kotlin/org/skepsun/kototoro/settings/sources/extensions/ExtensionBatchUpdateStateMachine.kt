package org.skepsun.kototoro.settings.sources.extensions

import java.util.ArrayDeque
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

internal class ExtensionBatchUpdateStateMachine {

    private val pendingPackages = ArrayDeque<String>()
    private val inProgressMutable = MutableStateFlow(false)

    val inProgress: StateFlow<Boolean> = inProgressMutable.asStateFlow()

    var currentPackage: String? = null
        private set

    var awaitingInstallerResult: Boolean = false
        private set

    fun start(packages: List<String>): Boolean {
        if (packages.isEmpty()) return false
        pendingPackages.clear()
        pendingPackages.addAll(packages)
        inProgressMutable.value = true
        return true
    }

    fun beginInstall(packageName: String) {
        currentPackage = packageName
        awaitingInstallerResult = false
    }

    fun markInstallerIntentDispatched() {
        awaitingInstallerResult = true
    }

    fun shouldCancelCurrent(packageName: String): Boolean {
        return inProgress.value && currentPackage == packageName
    }

    fun cancel(onCancelDownload: (String) -> Unit) {
        if (!inProgress.value) return
        inProgressMutable.value = false
        pendingPackages.clear()
        currentPackage?.takeIf { !awaitingInstallerResult }?.let(onCancelDownload)
        currentPackage = null
        awaitingInstallerResult = false
    }

    fun finishCurrentInstall(): NextAction {
        currentPackage = null
        awaitingInstallerResult = false
        return nextAction()
    }

    fun nextAction(): NextAction {
        if (!inProgress.value || currentPackage != null) {
            return finishIfIdle()
        }
        while (pendingPackages.isNotEmpty()) {
            return NextAction.InstallNext(pendingPackages.removeFirst())
        }
        return finishIfIdle()
    }

    private fun finishIfIdle(): NextAction {
        return if (inProgress.value && currentPackage == null && pendingPackages.isEmpty()) {
            inProgressMutable.value = false
            NextAction.Completed
        } else {
            NextAction.None
        }
    }

    sealed interface NextAction {
        data object None : NextAction
        data object Completed : NextAction
        data class InstallNext(val packageName: String) : NextAction
    }
}
