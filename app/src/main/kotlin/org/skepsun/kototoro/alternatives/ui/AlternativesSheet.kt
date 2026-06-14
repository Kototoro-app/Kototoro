package org.skepsun.kototoro.alternatives.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.viewModels
import dagger.hilt.android.AndroidEntryPoint
import org.skepsun.kototoro.R
import org.skepsun.kototoro.alternatives.ui.compose.AlternativesSheetContent
import org.skepsun.kototoro.core.model.getTitle
import org.skepsun.kototoro.core.nav.router
import org.skepsun.kototoro.core.ui.dialog.buildAlertDialog
import org.skepsun.kototoro.core.ui.sheet.BaseAdaptiveSheet
import org.skepsun.kototoro.core.ui.theme.KototoroTheme
import org.skepsun.kototoro.core.util.ext.observeEvent
import org.skepsun.kototoro.databinding.SheetAlternativesBinding
import org.skepsun.kototoro.parsers.model.Content

@AndroidEntryPoint
class AlternativesSheet : BaseAdaptiveSheet<SheetAlternativesBinding>() {

	private val viewModel by viewModels<AlternativesViewModel>()

	override fun onCreateViewBinding(inflater: LayoutInflater, container: ViewGroup?): SheetAlternativesBinding {
		return SheetAlternativesBinding.inflate(inflater, container, false)
	}

	override fun onViewBindingCreated(binding: SheetAlternativesBinding, savedInstanceState: Bundle?) {
		super.onViewBindingCreated(binding, savedInstanceState)
		disableFitToContents()
		binding.composeView.setViewCompositionStrategy(
			ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed,
		)
		binding.composeView.setContent {
			KototoroTheme {
				val list by viewModel.list.collectAsState()
				AlternativesSheetContent(
					items = list,
					onItemClick = { router.openResolvedDetails(it.manga) },
					onSourceClick = { router.openSearch(it.manga.source, viewModel.manga.title) },
					onMigrateClick = { confirmMigration(it.manga) },
					onRetry = { viewModel.retry() },
					onContinueSearch = { viewModel.continueSearch() },
				)
			}
		}
		viewModel.onMigrated.observeEvent(viewLifecycleOwner) {
			Toast.makeText(requireContext(), R.string.migration_completed, Toast.LENGTH_SHORT).show()
			router.openResolvedDetails(it)
			dismissAllowingStateLoss()
		}
	}

	override fun onApplyWindowInsets(v: View, insets: WindowInsetsCompat): WindowInsetsCompat = insets

	private fun confirmMigration(target: Content) {
		buildAlertDialog(requireContext(), isCentered = true) {
			setIcon(R.drawable.ic_replace)
			setTitle(R.string.manga_migration)
			setMessage(
				getString(
					R.string.migrate_confirmation,
					viewModel.manga.title,
					viewModel.manga.source.getTitle(context),
					target.title,
					target.source.getTitle(context),
				),
			)
			setNegativeButton(android.R.string.cancel, null)
			setPositiveButton(R.string.migrate) { _, _ ->
				viewModel.migrate(target)
			}
		}.show()
	}
}
