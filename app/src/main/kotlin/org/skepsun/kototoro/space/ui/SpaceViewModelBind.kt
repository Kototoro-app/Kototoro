package org.skepsun.kototoro.space.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel

/**
 * Resolves a [SpaceBindableViewModel] scoped to the current [LocalBrowseSpaceId] and binds it
 * to that space for the composition's lifetime.
 *
 * Moved out of `MainShellScene` so feature routes (home, browse, feed, …) can reuse the same
 * space-scoped binding without reaching into the shell.
 */
@Composable
internal inline fun <reified VM> spaceBoundHiltViewModel(owner: String): VM
    where VM : ViewModel, VM : SpaceBindableViewModel {
    val spaceId = LocalBrowseSpaceId.current
    val viewModel = hiltViewModel<VM>(key = spaceViewModelKey(owner, spaceId))
    LaunchedEffect(viewModel, spaceId) {
        viewModel.bindSpace(spaceId)
    }
    return viewModel
}
