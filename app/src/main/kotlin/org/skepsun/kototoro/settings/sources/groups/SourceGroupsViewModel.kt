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

	fun delete(id: String) {
		viewModelScope.launch {
			runAction(null) {
				repository.delete(id)
				_groups.value = repository.list()
			}
		}
	}

	fun applyGroup(id: String) {
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
			runAction(R.string.source_groups_imported) {
				repository.importFromUri(uri)
				_groups.value = repository.list()
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
