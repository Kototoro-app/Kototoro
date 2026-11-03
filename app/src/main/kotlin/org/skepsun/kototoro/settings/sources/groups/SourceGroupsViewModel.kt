package org.skepsun.kototoro.settings.sources.groups

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.skepsun.kototoro.R
import javax.inject.Inject

@HiltViewModel
class SourceGroupsViewModel @Inject constructor(
	@ApplicationContext private val context: Context,
	private val repository: SourceGroupRepository,
) : ViewModel() {

	private val _groups = MutableStateFlow<List<SourceGroup>>(emptyList())
	val groups: StateFlow<List<SourceGroup>> = _groups.asStateFlow()

	val progress: StateFlow<ApplyProgress> = repository.progress

	private val _message = MutableStateFlow<String?>(null)
	val message: StateFlow<String?> = _message.asStateFlow()

	init {
		reload()
	}

	fun reload() {
		viewModelScope.launch {
			runAction(null) { _groups.value = repository.list() }
		}
	}

	fun saveCurrentAsGroup(name: String) {
		viewModelScope.launch {
			runAction(R.string.source_groups_saved) {
				repository.saveCurrentAsGroup(name)
				_groups.value = repository.list()
			}
		}
	}

	fun createCleanGroup(name: String) {
		viewModelScope.launch {
			runAction(R.string.source_groups_saved) {
				repository.createCleanGroup(name)
				_groups.value = repository.list()
			}
		}
	}

	fun delete(id: String) {
		viewModelScope.launch {
			runAction(null) {
				repository.delete(id)
				_groups.value = repository.list()
			}
		}
	}

	private val _pendingApply = MutableStateFlow<Pair<String, SourceGroupApplyPreview>?>(null)

	/** Group waiting for the user to confirm the repositories and packages it would add. */
	val pendingApply: StateFlow<Pair<String, SourceGroupApplyPreview>?> = _pendingApply.asStateFlow()

	/** Applies right away when the group adds nothing; otherwise asks for confirmation first. */
	fun requestApply(id: String) {
		viewModelScope.launch {
			var direct = false
			runAction(null) {
				val preview = repository.previewApply(id)
				if (preview.needsConfirmation) _pendingApply.value = id to preview else direct = true
			}
			if (direct) applyGroup(id)
		}
	}

	fun cancelPendingApply() {
		_pendingApply.value = null
	}

	fun confirmPendingApply() {
		val id = _pendingApply.value?.first ?: return
		_pendingApply.value = null
		applyGroup(id)
	}

	private fun applyGroup(id: String) {
		viewModelScope.launch {
			runAction(null) {
				val errors = repository.apply(id)
				_message.value = if (errors.isEmpty()) {
					context.getString(R.string.source_groups_applied)
				} else {
					context.getString(R.string.source_groups_apply_errors, errors.size)
				}
			}
		}
	}

	fun exportGroup(id: String, uri: Uri) {
		viewModelScope.launch {
			runAction(R.string.source_groups_exported) { repository.exportToUri(id, uri) }
		}
	}

	fun importGroup(uri: Uri) {
		viewModelScope.launch {
			runAction(null) {
				val result = repository.importFromUri(uri)
				_groups.value = repository.list()
				_message.value = context.getString(
					if (result.updated) R.string.source_groups_updated else R.string.source_groups_imported,
				)
			}
		}
	}

	fun consumeMessage() {
		_message.value = null
	}

	private suspend fun runAction(successRes: Int?, block: suspend () -> Unit) {
		try {
			block()
			if (successRes != null) {
				_message.value = context.getString(successRes)
			}
		} catch (e: CancellationException) {
			throw e
		} catch (e: SourceGroupTooLargeException) {
			_message.value = context.getString(R.string.source_groups_file_too_large)
		} catch (e: SourceGroupNewerSchemaException) {
			_message.value = context.getString(R.string.source_groups_newer_schema)
		} catch (e: SourceGroupFormatException) {
			_message.value = context.getString(R.string.source_groups_invalid_file)
		} catch (e: Exception) {
			_message.value = context.getString(
				R.string.source_groups_error_generic,
				e.localizedMessage ?: e.javaClass.simpleName,
			)
		}
	}
}
