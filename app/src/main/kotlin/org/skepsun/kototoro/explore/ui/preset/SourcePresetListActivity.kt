package org.skepsun.kototoro.explore.ui.preset

import android.os.Bundle
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.collectLatest
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.ui.BaseComposeActivity

@AndroidEntryPoint
class SourcePresetListActivity : BaseComposeActivity() {
    private val viewModel by viewModels<SourcePresetListViewModel>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setComposeContent {
            val presets by viewModel.presets.collectAsStateWithLifecycle()
            SourcePresetListScreen(
                presets = presets,
                activePresetId = viewModel.activePresetId,
                sourceCount = viewModel::countSourcesForPreset,
                onBack = ::finish,
                onAdd = { startActivity(SourcePresetEditActivity.newIntent(this)) },
                onSelect = { preset ->
                    viewModel.setActivePreset(if (preset.id == viewModel.activePresetId) 0L else preset.id)
                },
                onEdit = { preset -> startActivity(SourcePresetEditActivity.newIntent(this, preset.id)) },
                onDelete = { preset -> viewModel.deletePreset(preset.id) },
            )
            LaunchedEffect(Unit) {
                viewModel.onPresetDeleted.collectLatest {
                    Toast.makeText(this@SourcePresetListActivity, R.string.preset_deleted, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
}
