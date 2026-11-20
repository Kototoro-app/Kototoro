package org.skepsun.kototoro.community.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.skepsun.kototoro.community.domain.CommunityCommentEntry
import org.skepsun.kototoro.community.domain.CommunityCommentsPage
import org.skepsun.kototoro.community.domain.CommunityCommentsRepository
import org.skepsun.kototoro.community.domain.CommunityExternalRef
import org.skepsun.kototoro.community.domain.CommunityRatingRefusal
import org.skepsun.kototoro.community.domain.CommunityRepository
import org.skepsun.kototoro.community.domain.CommunityWorkIdentity
import org.skepsun.kototoro.community.domain.CommunityWorkSummary
import org.skepsun.kototoro.community.nostr.CommunityReportType
import org.skepsun.kototoro.parsers.model.Content
import org.skepsun.kototoro.parsers.util.runCatchingCancellable
import java.util.Locale
import javax.inject.Inject

data class CommunityCommentsUiState(
    val isLoading: Boolean = true,
    val page: CommunityCommentsPage? = null,
    /** Mining, signing and handing the comment to the relays; comments take a second or two of proof of work. */
    val isPosting: Boolean = false,
    val refusal: CommunityRatingRefusal? = null,
    val replyTo: CommunityCommentEntry? = null,
)

/** One work's comment sheet (plan §7.2). Loads when the sheet opens and again after each change the reader makes. */
@HiltViewModel
class CommunityCommentsViewModel @Inject constructor(
    private val comments: CommunityCommentsRepository,
    private val repository: CommunityRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(CommunityCommentsUiState())
    val state: StateFlow<CommunityCommentsUiState> = _state

    private var target: Pair<Content, List<CommunityExternalRef>>? = null
    private var identity: CommunityWorkIdentity? = null
    private var summary: CommunityWorkSummary? = null
    private var loadJob: Job? = null

    fun bind(content: Content, refs: List<CommunityExternalRef>) {
        if (target == content to refs) return
        target = content to refs
        loadJob?.cancel()
        loadJob = viewModelScope.launch(Dispatchers.IO) {
            _state.update { it.copy(isLoading = true) }
            val identity = repository.identity(content, refs, resolve = false).also { identity = it }
            summary = repository.summary(identity)
            _state.update { it.copy(refusal = repository.refusal(identity)) }
            reload()
        }
    }

    fun refresh() {
        loadJob?.cancel()
        loadJob = viewModelScope.launch(Dispatchers.IO) { reload() }
    }

    fun replyTo(entry: CommunityCommentEntry?) = _state.update { it.copy(replyTo = entry) }

    fun post(text: String, spoiler: Boolean) {
        val identity = identity ?: return
        val parent = _state.value.replyTo?.event
        viewModelScope.launch(Dispatchers.IO) {
            _state.update { it.copy(isPosting = true) }
            val refusal = runCatchingCancellable {
                comments.post(identity, text.trim(), parent, spoiler, Locale.getDefault().language)
            }.getOrNull()
            _state.update { it.copy(isPosting = false, refusal = refusal ?: it.refusal, replyTo = null) }
            reload()
        }
    }

    fun delete(entry: CommunityCommentEntry) = change { comments.delete(entry.event) }

    fun report(entry: CommunityCommentEntry, type: CommunityReportType) = change { comments.report(entry.event, type) }

    fun block(entry: CommunityCommentEntry) = change { comments.block(entry.comment.pubkey) }

    private fun change(block: suspend () -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatchingCancellable { block() }
            reload()
        }
    }

    private suspend fun reload() {
        val identity = identity ?: return
        _state.update { it.copy(isLoading = true) }
        val page = runCatchingCancellable { comments.load(identity, summary) }.getOrNull()
        _state.update { it.copy(isLoading = false, page = page ?: it.page) }
    }
}
