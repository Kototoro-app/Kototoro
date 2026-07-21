package org.skepsun.kototoro.reader.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.graphics.Color
import androidx.core.os.bundleOf
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentContainerView
import androidx.fragment.app.commit
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.nav.AppRouter
import org.skepsun.kototoro.core.prefs.AppSettings
import org.skepsun.kototoro.details.ui.pager.ChaptersPagesSheet
import org.skepsun.kototoro.core.prefs.ReaderMode
import org.skepsun.kototoro.reader.ui.config.ReaderConfigSheet
import javax.inject.Inject

@AndroidEntryPoint
class EmbeddedReaderFragment : Fragment(), ReaderConfigSheet.Callback {

    @Inject
    lateinit var settings: AppSettings

    private val viewModel by viewModels<ReaderViewModel>()
    private var readerManager: ReaderManager? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = FrameLayout(requireContext()).apply {
        setBackgroundColor(Color.BLACK)
        addView(
            FragmentContainerView(context).apply { id = R.id.embedded_reader_viewer },
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val container = view.findViewById<FragmentContainerView>(R.id.embedded_reader_viewer)
        val manager = ReaderManager(childFragmentManager, container, settings)
        readerManager = manager
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.readerMode.collect { mode ->
                    if (mode != null && manager.currentMode != mode) manager.replace(mode)
                }
            }
        }
    }

    override fun onDestroyView() {
        readerManager?.currentReader?.getCurrentState()?.let(viewModel::saveCurrentState)
        readerManager = null
        super.onDestroyView()
    }

    fun requestClose(onFlushed: () -> Unit) {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val state = readerManager?.currentReader?.getCurrentState() ?: viewModel.getCurrentState()
                viewModel.flushForSpaceSwitch(state)
            } finally {
                onFlushed()
            }
        }
    }

    fun switchPageBy(delta: Int) {
        readerManager?.currentReader?.switchPageBy(delta)
    }

    fun switchChapterBy(delta: Int) {
        readerManager?.currentReader?.getCurrentState()?.let(viewModel::saveCurrentState)
        viewModel.switchChapterBy(delta)
    }

    fun toggleBookmark() {
        readerManager?.currentReader?.getCurrentState()?.let(viewModel::saveCurrentState)
        viewModel.toggleBookmark()
    }

    fun openChapters() {
        ChaptersPagesSheet().show(childFragmentManager, ChaptersPagesSheet::class.java.name)
    }

    fun openMore() {
        val mode = readerManager?.currentMode ?: return
        ReaderConfigSheet().apply {
            arguments = Bundle().apply { putInt(AppRouter.KEY_READER_MODE, mode.id) }
        }.show(childFragmentManager, ReaderConfigSheet::class.java.name)
    }

    override fun onReaderModeChanged(mode: ReaderMode) {
        readerManager?.currentReader?.getCurrentState()?.let(viewModel::saveCurrentState)
        viewModel.switchMode(mode)
    }

    override fun onDoubleModeChanged(isEnabled: Boolean) {
        readerManager?.setDoubleReaderMode(isEnabled)
    }

    override fun onSplitModeChanged(isEnabled: Boolean) = viewModel.reload()

    override fun onSavePageClick() = Unit

    override fun onScrollTimerClick(isLongClick: Boolean) = Unit

    override fun onBookmarkClick() = toggleBookmark()

    fun seekToPage(page: Int) {
        val state = viewModel.getCurrentState() ?: return
        val position = viewModel.content.value.pages.indexOfFirst {
            it.chapterId == state.chapterId && it.index == page
        }
        if (position >= 0) readerManager?.currentReader?.switchPageTo(position, smooth = false)
    }

    fun observeCockpitState(): Flow<EmbeddedReaderCockpitState> = combine(
        viewModel.currentReaderState,
        viewModel.content,
        viewModel.isBookmarkAdded,
    ) { state, content, isBookmarked ->
        val chapterPageCount = state?.let { current ->
            content.pages.count { it.chapterId == current.chapterId }
        } ?: 0
        EmbeddedReaderCockpitState(
            page = state?.page ?: 0,
            pageCount = chapterPageCount,
            isBookmarked = isBookmarked,
        )
    }

    fun scrollBy(delta: Int): Boolean =
        readerManager?.currentReader?.scrollBy(delta, smooth = false) == true

    companion object {
        fun newInstance(arguments: Bundle) = EmbeddedReaderFragment().apply {
            this.arguments = bundleOf().apply { putAll(arguments) }
        }
    }
}

data class EmbeddedReaderCommands(
    val previousChapter: () -> Unit,
    val nextChapter: () -> Unit,
    val toggleBookmark: () -> Unit,
    val seekToPage: (Int) -> Unit,
    val openChapters: () -> Unit,
    val openMore: () -> Unit,
)

data class EmbeddedReaderCockpitState(
    val page: Int = 0,
    val pageCount: Int = 0,
    val isBookmarked: Boolean = false,
)
