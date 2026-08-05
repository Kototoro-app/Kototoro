package org.skepsun.kototoro.explore.ui.preset

import android.os.Bundle
import android.widget.Toast
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.AndroidEntryPoint
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.ui.BaseComposeActivity
import org.skepsun.kototoro.core.util.ext.observeEvent

@AndroidEntryPoint
class SourcePresetListActivity : BaseComposeActivity() {
    private val viewModel by viewModels<SourcePresetListViewModel>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        viewModel.onPresetDeleted.observeEvent(this) {
            Toast.makeText(this, R.string.preset_deleted, Toast.LENGTH_SHORT).show()
        }
        setComposeContent {
            val presets by viewModel.presets.collectAsStateWithLifecycle()
            val activePresetId by viewModel.activePresetId.collectAsStateWithLifecycle()
            SourcePresetListScreen(
                presets = presets,
                activePresetId = activePresetId,
                sourceCount = viewModel::countSourcesForPreset,
                onBack = ::finish,
                onAdd = { startActivity(SourcePresetEditActivity.newIntent(this)) },
                onSelect = { preset ->
                    viewModel.setActivePreset(if (preset.id == activePresetId) 0L else preset.id)
                },
                onEdit = { preset -> startActivity(SourcePresetEditActivity.newIntent(this, preset.id)) },
                onDelete = { preset -> viewModel.deletePreset(preset.id) },
            )
        }
    }
}
