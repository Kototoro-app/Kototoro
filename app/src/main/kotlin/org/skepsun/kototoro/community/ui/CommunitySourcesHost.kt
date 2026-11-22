package org.skepsun.kototoro.community.ui

import androidx.compose.runtime.Composable
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.skepsun.kototoro.community.domain.CommunityWorkFinder
import org.skepsun.kototoro.core.library.community.CommunitySourceOption
import org.skepsun.kototoro.parsers.model.Content
import javax.inject.Inject

/** The "other sources" list of one community work; [choices] null while they are worked out. */
data class CommunitySourcesRequest(
    val groupId: String,
    val title: String,
    val choices: List<CommunitySourceChoice>? = null,
    val options: Map<String, CommunitySourceOption> = emptyMap(),
)

@HiltViewModel
class CommunitySourcesViewModel @Inject constructor(private val finder: CommunityWorkFinder) : ViewModel() {

    private val _request = MutableStateFlow<CommunitySourcesRequest?>(null)
    val request: StateFlow<CommunitySourcesRequest?> = _request

    fun show(groupId: String, title: String) {
        _request.value = CommunitySourcesRequest(groupId, title)
        viewModelScope.launch {
            val options = withContext(Dispatchers.IO) { finder.options(groupId) }
            _request.update { current ->
                current?.takeIf { it.groupId == groupId }?.copy(
                    choices = options.map { it.second },
                    options = options.associate { it.second.key to it.first },
                )
            }
        }
    }

    fun dismiss() {
        _request.value = null
    }

    fun pick(choice: CommunitySourceChoice, onFound: (Content) -> Unit, onMissing: (String) -> Unit) {
        val request = _request.value ?: return
        val option = request.options[choice.key] ?: return
        _request.value = null
        viewModelScope.launch {
            val found = withContext(Dispatchers.IO) { finder.findIn(option, request.groupId, request.title) }
            if (found != null) onFound(found) else onMissing(request.title)
        }
    }
}

/**
 * The "other sources" picker a long press on a community work opens, drawn by the shared [CommunitySourcePicker]:
 * [show] is what the rail's long press calls.
 */
@Composable
fun rememberCommunitySources(
    onOpenContent: (Content) -> Unit,
    onSearchTitle: (String) -> Unit,
): (id: String, title: String) -> Unit {
    val viewModel = hiltViewModel<CommunitySourcesViewModel>()
    val request = viewModel.request.collectAsStateWithLifecycle().value
    if (request != null) {
        CommunitySourcePicker(
            title = request.title,
            choices = request.choices,
            onPick = { viewModel.pick(it, onOpenContent, onSearchTitle) },
            onSearchAll = {
                viewModel.dismiss()
                onSearchTitle(request.title)
            },
            onDismiss = viewModel::dismiss,
        )
    }
    return viewModel::show
}
