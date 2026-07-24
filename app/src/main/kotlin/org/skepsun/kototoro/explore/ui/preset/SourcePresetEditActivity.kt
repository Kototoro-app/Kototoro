package org.skepsun.kototoro.explore.ui.preset

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.collectLatest
import org.skepsun.kototoro.core.nav.AppRouter
import org.skepsun.kototoro.core.ui.BaseComposeActivity

@AndroidEntryPoint
class SourcePresetEditActivity : BaseComposeActivity() {
    private val viewModel by viewModels<SourcePresetEditViewModel>()
    private var title by mutableStateOf("")
    private var selectedLocales by mutableStateOf<Set<String>>(emptySet())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setComposeContent {
            val preset by viewModel.preset.collectAsStateWithLifecycle()
            val locales by viewModel.allLocales.collectAsStateWithLifecycle()
            val isLoading by viewModel.isLoading.collectAsStateWithLifecycle()
            LaunchedEffect(preset) {
                preset?.let {
                    title = it.title
                    selectedLocales = it.languages
                }
            }
            LaunchedEffect(Unit) {
                viewModel.onSaved.collectLatest { finishAfterTransition() }
            }
            SourcePresetEditScreen(
                title = title,
                locales = locales.sorted(),
                selectedLocales = selectedLocales,
                isLoading = isLoading,
                error = null,
                onBack = ::finish,
                onTitleChange = { title = it },
                onLocaleToggle = { locale ->
                    selectedLocales = if (locale in selectedLocales) selectedLocales - locale else selectedLocales + locale
                },
                onSave = { viewModel.save(title.trim(), selectedLocales) },
            )
        }
    }

    companion object {
        fun newIntent(context: Context, presetId: Long = SourcePresetEditViewModel.NO_ID): Intent =
            Intent(context, SourcePresetEditActivity::class.java).putExtra(AppRouter.KEY_ID, presetId)
    }
}
