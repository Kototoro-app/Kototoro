package org.skepsun.kototoro.community.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.skepsun.kototoro.community.domain.CommunityExternalRef
import org.skepsun.kototoro.community.domain.CommunityRatingRefusal
import org.skepsun.kototoro.community.domain.CommunityRatingSummary
import org.skepsun.kototoro.community.domain.CommunityRepository
import org.skepsun.kototoro.community.domain.CommunityWorkIdentity
import org.skepsun.kototoro.core.prefs.AppSettings
import org.skepsun.kototoro.core.prefs.observeAsFlow
import org.skepsun.kototoro.parsers.model.Content
import org.skepsun.kototoro.parsers.util.runCatchingCancellable
import javax.inject.Inject

data class CommunityRatingUiState(
    /** The work's keys are still being worked out (tracker id searches can take seconds). */
    val isLoading: Boolean,
    val rating: CommunityRatingSummary?,
    val myStars: Int?,
    /** Comments the index counted; the sheet itself reads the relays. */
    val comments: Int,
    /** Why the reader cannot rate this work, or null when they can. */
    val refusal: CommunityRatingRefusal?,
    val isPublishing: Boolean,
)

/**
 * The community rating row on a details page (plan §7.2). Kept apart from the details screen's own view model: nothing
 * here runs, and no tracker site is searched, unless the reader turned the community on.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class CommunityRatingViewModel @Inject constructor(
    private val repository: CommunityRepository,
    settings: AppSettings,
) : ViewModel() {

    private data class Target(val content: Content, val refs: List<CommunityExternalRef>)

    private val target = MutableStateFlow<Target?>(null)
    private val publishing = MutableStateFlow(false)
    private val enabled = settings.observeAsFlow(
        setOf(AppSettings.KEY_COMMUNITY_ENABLED, AppSettings.KEY_COMMUNITY_NSFW),
    ) { isCommunityEnabled to isCommunityNsfwRatingEnabled }

    private val loaded = combine(target, enabled) { target, enabled -> target.takeIf { enabled.first } to enabled }
        .flatMapLatest { (target, _) ->
            if (target == null) {
                flowOf<Loaded?>(null)
            } else {
                flow {
                    // What is already known (titles, the reader's bindings, earlier searches) answers at once; the
                    // tracker searches can take tens of seconds and only replace it when they found another key.
                    val known = repository.identity(target.content, target.refs, resolve = false)
                    emit(Loaded(known, null, isLoading = true))
                    val knownSummary = repository.summary(known)
                    emit(Loaded(known, knownSummary, isLoading = false))
                    val resolved = repository.identity(target.content, target.refs, resolve = true)
                    if (resolved.keys != known.keys) {
                        emit(Loaded(resolved, repository.summary(resolved) ?: knownSummary, isLoading = false))
                    }
                }.flowOn(Dispatchers.IO)
            }
        }

    private val myStars = target.flatMapLatest { target -> target?.let { repository.observeMyRating(it.content.id) } ?: flowOf(null) }

    val state: StateFlow<CommunityRatingUiState?> = combine(loaded, myStars, publishing) { loaded, myStars, publishing ->
        loaded ?: return@combine null
        CommunityRatingUiState(
            isLoading = loaded.isLoading,
            rating = loaded.summary?.rating,
            myStars = myStars,
            comments = loaded.summary?.comments ?: 0,
            refusal = repository.refusal(loaded.identity),
            isPublishing = publishing,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun bind(content: Content, refs: List<CommunityExternalRef>) {
        val next = Target(content, refs)
        if (target.value != next) target.value = next
        // Until a background worker retries the outbox, each details page the reader opens gives it another try.
        if (!outboxTried && repository.isEnabled) {
            outboxTried = true
            viewModelScope.launch(Dispatchers.IO) { runCatchingCancellable { repository.flushOutbox() } }
        }
    }

    private var outboxTried = false

    fun rate(stars: Int) = publish { identity, mangaId -> repository.rate(mangaId, identity, stars) }

    fun withdraw() = publish { _, mangaId -> repository.withdraw(mangaId) }

    private fun publish(block: suspend (CommunityWorkIdentity, Long) -> Unit) {
        val target = target.value ?: return
        viewModelScope.launch(Dispatchers.IO) {
            publishing.value = true
            try {
                runCatchingCancellable {
                    block(repository.identity(target.content, target.refs, resolve = false), target.content.id)
                }
            } finally {
                publishing.value = false
            }
        }
    }

    private data class Loaded(
        val identity: CommunityWorkIdentity,
        val summary: org.skepsun.kototoro.community.domain.CommunityWorkSummary?,
        val isLoading: Boolean,
    )
}
