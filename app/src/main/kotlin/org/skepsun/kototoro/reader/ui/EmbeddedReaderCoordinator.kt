package org.skepsun.kototoro.reader.ui

import android.content.Intent
import android.os.Bundle
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.skepsun.kototoro.core.nav.AppRouter
import org.skepsun.kototoro.space.domain.SpaceId
import org.skepsun.kototoro.space.domain.SpaceRepository
import javax.inject.Inject
import javax.inject.Singleton

data class EmbeddedReaderRequest(
    val id: Long,
    val arguments: Bundle,
    val spaceId: SpaceId,
)

@Singleton
class EmbeddedReaderCoordinator @Inject constructor(
    private val spaceRepository: SpaceRepository,
) {

    private val mutableRequest = MutableStateFlow<EmbeddedReaderRequest?>(null)
    val request: StateFlow<EmbeddedReaderRequest?> = mutableRequest.asStateFlow()
    private var closeHandler: (() -> Unit)? = null
    private var afterClose: (() -> Unit)? = null
    private var preserveCurrentSession = false
    private val sessions = mutableMapOf<SpaceId, EmbeddedReaderRequest>()

    fun open(intent: Intent) {
        val arguments = Bundle(intent.extras ?: Bundle()).apply {
            intent.data?.let { putParcelable(AppRouter.KEY_DATA, it) }
        }
        val request = EmbeddedReaderRequest(
            id = System.nanoTime(),
            arguments = arguments,
            spaceId = spaceRepository.activeSpace.value,
        )
        sessions[request.spaceId] = request
        mutableRequest.value = request
    }

    fun close(afterClose: () -> Unit = {}) {
        preserveCurrentSession = false
        this.afterClose = afterClose
        closeHandler?.invoke() ?: completeClose()
    }

    fun suspendForSpaceSwitch(afterClose: () -> Unit) {
        preserveCurrentSession = true
        this.afterClose = afterClose
        closeHandler?.invoke() ?: completeClose()
    }

    fun restore(spaceId: SpaceId): Boolean {
        val request = sessions[spaceId] ?: return false
        mutableRequest.value = request.copy(id = System.nanoTime())
        return true
    }

    fun installCloseHandler(handler: (() -> Unit)?) {
        closeHandler = handler
    }

    fun completeClose() {
        closeHandler = null
        if (!preserveCurrentSession) {
            mutableRequest.value?.spaceId?.let(sessions::remove)
        }
        preserveCurrentSession = false
        mutableRequest.value = null
        val action = afterClose
        afterClose = null
        action?.invoke()
    }
}
