package org.skepsun.kototoro.settings.sources.jsonsource.edit

import android.os.Bundle
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.ui.BaseComposeActivity

@AndroidEntryPoint
class JsonSourceEditActivity : BaseComposeActivity() {
    private val viewModel: JsonSourceEditViewModel by viewModels()
    private var draft by mutableStateOf(SourceEditData("", "", null, null, null, true))

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        intent.getStringExtra(EXTRA_SOURCE_ID)?.let(viewModel::loadSource)
        lifecycleScope.launch {
            viewModel.source.collect { it?.let { draft = it } }
        }
        lifecycleScope.launch {
            viewModel.saveResult.collect { result ->
                when (result) {
                    is SaveResult.Success -> {
                        Toast.makeText(this@JsonSourceEditActivity, R.string.saved, Toast.LENGTH_SHORT).show()
                        finish()
                    }
                    is SaveResult.Error -> Toast.makeText(
                        this@JsonSourceEditActivity,
                        result.message,
                        Toast.LENGTH_LONG,
                    ).show()
                    null -> Unit
                }
            }
        }
        setComposeContent {
            JsonSourceEditScreen(
                source = draft,
                isEdit = intent.hasExtra(EXTRA_SOURCE_ID),
                onBack = ::finish,
                onSave = { viewModel.saveSource(draft) },
                onSourceChange = { draft = it },
            )
        }
    }

    companion object {
        const val EXTRA_SOURCE_ID = "source_id"
    }
}
