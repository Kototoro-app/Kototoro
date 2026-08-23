package eu.kanade.tachiyomi.source.model

import android.net.Uri
import eu.kanade.tachiyomi.network.ProgressListener
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

/**
 * Mihon-compatible Page class.
 * Ported from Mihon source-api for extension compatibility.
 * 
 * Includes [uri] and [ProgressListener] for binary compatibility with extensions.
 * Also carries the Tsundoku novel additions: [text], [statusFlow] and [progressFlow].
 */
@Serializable
open class Page @JvmOverloads constructor(
    var index: Int,
    var url: String = "",
    var imageUrl: String? = null,
    @Transient var uri: Uri? = null,
) : ProgressListener {

    val number: Int
        get() = index + 1

    /**
     * Novel text content. This is a body property (not a constructor parameter)
     * to preserve binary compatibility with extensions compiled against the
     * upstream 4-param Page(index, url, imageUrl, uri) constructor.
     */
    @Transient
    var text: String? = null

    @Transient
    private val _statusFlow = MutableStateFlow<State>(State.Queue)

    @Transient
    val statusFlow = _statusFlow.asStateFlow()

    @Transient
    var status: State
        get() = _statusFlow.value
        set(value) {
            _statusFlow.value = value
        }

    @Transient
    private val _progressFlow = MutableStateFlow(0)

    @Transient
    val progressFlow = _progressFlow.asStateFlow()

    @Transient
    var progress: Int
        get() = _progressFlow.value
        set(value) {
            _progressFlow.value = value
        }

    override fun update(bytesRead: Long, contentLength: Long, done: Boolean) {
        progress = if (contentLength > 0) {
            (100 * bytesRead / contentLength).toInt()
        } else {
            -1
        }
    }

    fun copy(
        index: Int = this.index,
        url: String = this.url,
        imageUrl: String? = this.imageUrl,
    ): Page = Page(index, url, imageUrl)

    sealed interface State {
        data object Queue : State
        data object LoadPage : State
        data object DownloadImage : State
        data object Ready : State
        data class Error(val error: Throwable) : State
    }
}
